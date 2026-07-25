//! Native graphical UI for mc-graft (egui/eframe), over the same core as the CLI: discover JVMs, build an agent for a
//! payload, and inject it into a running Minecraft — with a live log. Long operations run on background threads and
//! stream lines back through a channel so the window never blocks.

use crate::store::{GameDir, InjectConfig, Store};
use crate::{attach, build, config, modinfo, procs};
use eframe::egui;
use std::path::PathBuf;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::time::{Duration, Instant};

pub fn run() -> Result<(), String> {
    let opts = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([880.0, 620.0])
            .with_min_inner_size([680.0, 460.0])
            .with_title("mc-graft"),
        ..Default::default()
    };
    eframe::run_native("mc-graft", opts, Box::new(|_cc| Ok(Box::new(App::new()))))
        .map_err(|e| format!("failed to start the GUI: {e}"))
}

enum Msg {
    Line(String),
    BuildDone(Result<PathBuf, String>),
    InjectDone(Result<(), String>),
}

#[derive(Clone, Copy, PartialEq)]
enum BuildMode {
    /// Drive Gradle's agentJar task (needs a JDK + the mc-graft repo checkout).
    Gradle,
    /// Splice the payload into the precompiled base jar in-process (no JDK, no Gradle).
    NoJdk,
}

struct App {
    // discovery
    jvms: Vec<procs::Jvm>,
    selected: Option<i32>,
    /// Whether the selected JVM's detail window is open.
    show_details: bool,
    last_refresh: Instant,
    // build inputs
    repo: Option<PathBuf>,
    payload: String,
    payload_info: Option<Result<modinfo::ModInfo, String>>,
    info_for: String,
    cfg: config::AgentConfig,
    presets: Vec<config::Preset>,
    preset: String,
    build_preset_name: String,
    agent_name: String,
    build_mode: BuildMode,
    base_jar: String,
    patches: Vec<String>,       // payload patch bundles/overlays applied before packing (no-JDK path)
    no_verify_base: bool,       // ignore each patch's [target] base guard
    // inject inputs
    agent_jar: String,
    game_dir: GameDir,
    custom_dir: String,
    tolerate: bool,
    inject_preset: String,
    inject_preset_name: String,
    // saved presets (persisted)
    store: Store,
    // runtime
    log: Vec<String>,
    busy: bool,
    status: String,
    rx: Receiver<Msg>,
    tx: Sender<Msg>,
}

impl App {
    fn new() -> Self {
        let (tx, rx) = channel();
        let repo = build::find_repo_root(None).ok();
        let presets = repo.as_ref().map(|r| config::discover_presets(r)).unwrap_or_default();
        // Locate the precompiled base jar; if not yet built but a repo is present, pre-fill its canonical build path so
        // the Quick-pack field guides the user to `./gradlew agentBase`.
        let base_jar = crate::pack::find_base_jar(repo.as_deref())
            .or_else(|| repo.as_ref().map(|r| r.join("build/agent/mc-graft-base.jar")));
        // Default to Gradle when a repo checkout is present (fully featured); otherwise the no-JDK packer, so a standalone
        // install (binary + base jar, no JDK/repo) can build agents out of the box.
        let build_mode = if repo.is_some() { BuildMode::Gradle } else { BuildMode::NoJdk };
        let mut app = App {
            jvms: Vec::new(),
            selected: None,
            show_details: false,
            last_refresh: Instant::now() - Duration::from_secs(10),
            repo,
            payload: String::new(),
            payload_info: None,
            info_for: String::new(),
            cfg: config::AgentConfig::default(),
            presets,
            preset: "None".into(),
            build_preset_name: String::new(),
            agent_name: "mc-graft-agent.jar".into(),
            build_mode,
            base_jar: base_jar.map(|p| p.display().to_string()).unwrap_or_default(),
            patches: Vec::new(),
            no_verify_base: false,
            agent_jar: String::new(),
            game_dir: GameDir::ClientDir,
            custom_dir: String::new(),
            tolerate: false,
            inject_preset: String::new(),
            inject_preset_name: String::new(),
            store: Store::load(),
            log: Vec::new(),
            busy: false,
            status: "Ready.".into(),
            rx,
            tx,
        };
        app.refresh();
        app
    }

    fn refresh(&mut self) {
        self.jvms = procs::list_jvms();
        self.last_refresh = Instant::now();
        if self.selected.is_none() {
            self.selected = self.jvms.iter().find(|j| j.is_minecraft).map(|j| j.pid);
        }
    }

    /// Point the build at a payload jar: read its mod info, auto-name the agent, and (for a known entrypoint-less mod
    /// with a bundled example config) pre-fill the bootstrap config.
    fn set_payload(&mut self, path: PathBuf) {
        self.payload = path.display().to_string();
        let info = modinfo::read_jar(&path);
        match &info {
            Ok(m) => {
                self.status = format!("Loaded {}", m.summary());
                if !m.id.is_empty() {
                    self.agent_name = format!("{}-agent.jar", m.id);
                }
                // A mod with no entrypoints needs a bootstrap override; auto-apply a bundled preset if one matches its id.
                if m.needs_bootstrap_config() && self.cfg.is_empty() {
                    if let Some(p) = self.presets.iter().find(|p| p.name == m.id) {
                        if let Ok(text) = std::fs::read_to_string(&p.path) {
                            self.cfg = config::AgentConfig::from_properties(&text);
                            self.preset = p.name.clone();
                            self.log_line(format!("[info] {} has no entrypoint — applied bundled preset '{}'", m.id, p.name));
                        }
                    } else {
                        self.log_line(format!("[warn] {} has no fabric entrypoint; set the event bootstrap under Advanced (no bundled preset for this id)", m.id));
                    }
                }
            }
            Err(e) => self.status = format!("Not a Fabric mod: {e}"),
        }
        self.payload_info = Some(info);
        self.info_for = self.payload.clone();
    }

    fn log_line(&mut self, s: impl Into<String>) {
        self.log.push(s.into());
        if self.log.len() > 5000 {
            self.log.drain(0..self.log.len() - 5000);
        }
    }

    fn drain(&mut self) {
        while let Ok(m) = self.rx.try_recv() {
            match m {
                Msg::Line(l) => self.log_line(l),
                Msg::BuildDone(Ok(jar)) => {
                    self.log_line(format!("[ok] agent built: {}", jar.display()));
                    self.agent_jar = jar.display().to_string();
                    if let Some(n) = jar.file_name() {
                        self.status = format!("Built {}", n.to_string_lossy());
                    }
                    self.busy = false;
                }
                Msg::BuildDone(Err(e)) => {
                    self.log_line(format!("[error] build failed: {e}"));
                    self.status = "Build failed.".into();
                    self.busy = false;
                }
                Msg::InjectDone(Ok(())) => {
                    self.status = "Injection finished.".into();
                    self.busy = false;
                }
                Msg::InjectDone(Err(e)) => {
                    self.log_line(format!("[error] inject error: {e}"));
                    self.status = "Injection failed.".into();
                    self.busy = false;
                }
            }
        }
    }

    fn start_build(&mut self, ctx: &egui::Context) {
        if self.payload.trim().is_empty() {
            self.log_line("[warn] pick a payload mod jar first");
            return;
        }
        if self.build_mode == BuildMode::NoJdk {
            self.start_pack(ctx);
            return;
        }
        let Some(repo) = self.repo.clone() else {
            self.log_line("[warn] no mc-graft repo root found (set it via the CLI --repo, or run from the repo)");
            return;
        };
        self.busy = true;
        self.status = "Building agent…".into();
        self.log_line("---- build ----");
        // Serialize the structured config to a temp .properties the build consumes (skip if nothing is set).
        let bootstrap_config = if self.cfg.is_empty() {
            None
        } else {
            let p = std::env::temp_dir().join("mcgraft-bootstrap.properties");
            match std::fs::write(&p, self.cfg.to_properties()) {
                Ok(()) => Some(p),
                Err(e) => {
                    self.log_line(format!("[warn] could not write temp config: {e}"));
                    None
                }
            }
        };
        let req = build::BuildRequest {
            payload: PathBuf::from(self.payload.trim()),
            bootstrap_config,
            mixin_targets: None,
            payload_deps: None,
            agent_name: opt_str(&self.agent_name),
        };
        let tx = self.tx.clone();
        let ctx = ctx.clone();
        spawn_worker(tx.clone(), ctx.clone(), Msg::BuildDone, move || {
            let tx2 = tx.clone();
            let ctx2 = ctx.clone();
            let res = build::build_agent(&repo, &req, move |l| {
                let _ = tx2.send(Msg::Line(l.to_string()));
                ctx2.request_repaint();
            });
            let _ = tx.send(Msg::BuildDone(res));
            ctx.request_repaint();
        });
    }

    /// JDK-free build: splice the payload into the precompiled base jar in-process (pack.rs). Needs the mod info
    /// (already parsed for the info card) and a base jar; no Gradle, no JDK.
    fn start_pack(&mut self, ctx: &egui::Context) {
        let base = PathBuf::from(self.base_jar.trim());
        if !base.is_file() {
            self.log_line("[warn] no base jar — build it once with `./gradlew agentBase`, or point 'Base jar' at mc-graft-base.jar");
            return;
        }
        let info0 = match &self.payload_info {
            Some(Ok(m)) => m.clone(),
            _ => {
                self.log_line("[warn] payload is not a readable Fabric mod jar");
                return;
            }
        };
        let payload = PathBuf::from(self.payload.trim());
        let out = self
            .repo
            .clone()
            .map(|r| r.join("build/agent").join(&self.agent_name))
            .unwrap_or_else(|| std::env::temp_dir().join(&self.agent_name));
        if let Some(d) = out.parent() {
            let _ = std::fs::create_dir_all(d);
        }
        let cfg = self.cfg.clone();
        let patches: Vec<PathBuf> = self
            .patches
            .iter()
            .map(|s| PathBuf::from(s.trim()))
            .filter(|p| !p.as_os_str().is_empty())
            .collect();
        let no_verify_base = self.no_verify_base;

        self.busy = true;
        self.status = "Packing agent (no JDK)…".into();
        self.log_line("---- pack (no JDK) ----");
        let tx = self.tx.clone();
        let ctx = ctx.clone();
        spawn_worker(tx.clone(), ctx.clone(), Msg::BuildDone, move || {
            let (tx_a, ctx_a) = (tx.clone(), ctx.clone());
            let (tx_b, ctx_b) = (tx.clone(), ctx.clone());
            let result: Result<PathBuf, String> = (|| {
                // Apply patches to the payload first — a merge on fabric.mod.json changes the mod info below.
                let patched = if patches.is_empty() {
                    None
                } else {
                    crate::patch::apply_patches(&payload, &patches, &crate::patch::Options { no_verify_base }, |l| {
                        let _ = tx_a.send(Msg::Line(l.to_string()));
                        ctx_a.request_repaint();
                    })?
                };
                let effective = patched.as_ref().map(|t| t.path()).unwrap_or(&payload);
                let info = if patched.is_some() { crate::modinfo::read_jar(effective)? } else { info0.clone() };
                let r = crate::pack::pack_agent(&base, effective, &info, &cfg, &out, |l| {
                    let _ = tx_b.send(Msg::Line(l.to_string()));
                    ctx_b.request_repaint();
                })
                .map(|()| out.clone());
                drop(patched); // delete the patched temp jar (NamedTempFile RAII)
                r
            })();
            let _ = tx.send(Msg::BuildDone(result));
            ctx.request_repaint();
        });
    }

    fn start_inject(&mut self, ctx: &egui::Context) {
        let Some(pid) = self.selected else {
            self.log_line("[warn] select a target JVM first");
            return;
        };
        let agent = PathBuf::from(self.agent_jar.trim());
        if !agent.is_file() {
            self.log_line("[warn] agent jar not found - build one or pick it");
            return;
        }
        // Assemble agent args (ARG_SEP-joined), then a temp log path we tail.
        let log_path = std::env::temp_dir().join(format!("mcgraft-inject-{pid}.log"));
        let _ = std::fs::write(&log_path, b""); // truncate so we tail only this run
        let mut parts = vec![format!("logFile={}", log_path.display())];
        match self.game_dir {
            GameDir::ClientDir => {}
            GameDir::WorkingDir => parts.push("gameDir=.".into()),
            GameDir::Custom if !self.custom_dir.trim().is_empty() => {
                parts.push(format!("gameDir={}", self.custom_dir.trim()))
            }
            GameDir::Custom => {}
        }
        if self.tolerate {
            parts.push("tolerateBaselineFailures=true".into());
        }
        let agent_args = parts.join("\u{1}");

        self.busy = true;
        self.status = format!("Injecting into PID {pid}…");
        self.log_line(format!("---- inject -> PID {pid} ----"));
        let tx = self.tx.clone();
        let ctx = ctx.clone();
        spawn_worker(tx.clone(), ctx.clone(), Msg::InjectDone, move || {
            match attach::load_agent(pid, &agent, &agent_args) {
                Ok(r) if r.jvm_status == 0 && r.agent_rc == 0 => {
                    let _ = tx.send(Msg::Line("agent delivered (Agent_OnAttach=0)".into()));
                    ctx.request_repaint();
                    tail_log(&log_path, &tx, &ctx);
                    let _ = tx.send(Msg::InjectDone(Ok(())));
                }
                // The load command was accepted but agentmain failed inside the client. Still tail the log so the
                // stack trace is visible, then report failure — this must not surface as "Injection finished."
                Ok(r) if r.jvm_status == 0 => {
                    let _ = tx.send(Msg::Line(format!("agentmain FAILED (Agent_OnAttach={})", r.agent_rc)));
                    ctx.request_repaint();
                    tail_log(&log_path, &tx, &ctx);
                    let _ = tx.send(Msg::InjectDone(Err(format!(
                        "agentmain failed inside the client (Agent_OnAttach={}): {}",
                        r.agent_rc,
                        r.output.trim()
                    ))));
                }
                Ok(r) => {
                    let _ = tx.send(Msg::InjectDone(Err(format!(
                        "JVM rejected the load command (status {}): {}",
                        r.jvm_status,
                        r.output.trim()
                    ))));
                }
                Err(e) => {
                    let _ = tx.send(Msg::InjectDone(Err(e)));
                }
            }
            ctx.request_repaint();
        });
    }
}

impl App {
    /// Detail window for the selected JVM: what it is, whether mc-graft can inject into it, and the raw command line
    /// the identification was derived from (so a wrong guess is diagnosable rather than just wrong).
    fn details_window(&mut self, ctx: &egui::Context) {
        if !self.show_details {
            return;
        }
        let Some(jvm) = self.selected.and_then(|pid| self.jvms.iter().find(|j| j.pid == pid).cloned()) else {
            self.show_details = false; // selection went away (process exited between refreshes)
            return;
        };
        let mut open = true;
        egui::Window::new(format!("JVM {} details", jvm.pid))
            .open(&mut open)
            .default_width(560.0)
            .collapsible(false)
            .show(ctx, |ui| {
                let row = |ui: &mut egui::Ui, k: &str, v: &str| {
                    ui.horizontal(|ui| {
                        ui.add_sized([120.0, 18.0], egui::Label::new(egui::RichText::new(k).strong()));
                        ui.label(v);
                    });
                };
                let dash = |o: &Option<String>| o.clone().unwrap_or_else(|| "—".into());

                row(ui, "Minecraft", if jvm.is_minecraft { "yes" } else { "not detected" });
                row(ui, "MC version", &dash(&jvm.mc_version));
                let loader = match &jvm.loader_version {
                    Some(v) => format!("{} {v}", jvm.loader.label()),
                    None => jvm.loader.label().to_string(),
                };
                row(ui, "Mod loader", &loader);
                row(ui, "Main class", &jvm.main);
                row(ui, "Game dir", &dash(&jvm.game_dir));
                row(ui, "Java binary", &dash(&jvm.java_exe));

                ui.add_space(6.0);
                if !jvm.is_minecraft {
                    ui.colored_label(
                        egui::Color32::from_rgb(0xd0, 0x90, 0x30),
                        "This doesn't look like Minecraft. Injecting is still allowed, but the agent expects a client.",
                    );
                } else if !jvm.loader.supported() {
                    ui.colored_label(
                        egui::Color32::from_rgb(0xd0, 0x90, 0x30),
                        format!(
                            "mc-graft has no platform for {} — supported loaders are Vanilla, Fabric and NeoForge.",
                            jvm.loader.label()
                        ),
                    );
                } else {
                    ui.colored_label(egui::Color32::from_rgb(0x40, 0xa0, 0x40), "Supported target.");
                }

                ui.add_space(6.0);
                ui.collapsing("Full command line", |ui| {
                    ui.horizontal(|ui| {
                        if ui.button("Copy").clicked() {
                            ui.output_mut(|o| o.copied_text = jvm.cmdline.clone());
                        }
                        ui.weak("everything above is derived from this");
                    });
                    egui::ScrollArea::vertical().max_height(180.0).show(ui, |ui| {
                        ui.add(egui::Label::new(egui::RichText::new(&jvm.cmdline).monospace()).wrap());
                    });
                });
            });
        self.show_details = open;
    }
}

/// Spawn a GUI worker that ALWAYS delivers a terminal result, even if its body panics.
///
/// `busy` is cleared only when a `BuildDone`/`InjectDone` arrives, and `App` holds its own clone of the sender, so a
/// dead worker never disconnects the channel to signal the loss. Without this, a panic anywhere in a worker — including
/// inside the third-party zip/class-file parsers, which run on user-supplied jars — leaves the UI spinning with every
/// button disabled and no recovery short of killing the process.
fn spawn_worker<F, T>(tx: std::sync::mpsc::Sender<Msg>, ctx: egui::Context, done: fn(Result<T, String>) -> Msg, body: F)
where
    F: FnOnce() + Send + 'static,
    T: Send + 'static,
{
    std::thread::spawn(move || {
        if std::panic::catch_unwind(std::panic::AssertUnwindSafe(body)).is_err() {
            let _ = tx.send(done(Err("internal error: the worker thread panicked (backtrace on stderr)".into())));
            ctx.request_repaint();
        }
    });
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.drain();
        if self.last_refresh.elapsed() > Duration::from_secs(3) {
            self.refresh();
        }
        ctx.request_repaint_after(Duration::from_millis(400));

        // Drag & drop: a dropped .jar becomes the payload (its fabric.mod.json is read for version info).
        let dropped: Vec<PathBuf> = ctx.input(|i| {
            i.raw.dropped_files.iter().filter_map(|f| f.path.clone()).collect()
        });
        if let Some(jar) = dropped.into_iter().find(|p| p.extension().map(|e| e.eq_ignore_ascii_case("jar")).unwrap_or(false)) {
            self.set_payload(jar);
        }
        // While something is hovering over the window, show a "drop to load" hint.
        let hovering = ctx.input(|i| !i.raw.hovered_files.is_empty());
        if hovering {
            let screen = ctx.screen_rect();
            let p = ctx.layer_painter(egui::LayerId::new(egui::Order::Foreground, egui::Id::new("dnd")));
            p.rect_filled(screen, 0.0, egui::Color32::from_black_alpha(160));
            p.text(screen.center(), egui::Align2::CENTER_CENTER, "Drop a mod jar to load it", egui::FontId::proportional(28.0), egui::Color32::WHITE);
        }

        egui::TopBottomPanel::top("top").show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.heading("mc-graft");
                ui.separator();
                ui.label(&self.status);
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if self.busy {
                        ui.spinner();
                    }
                });
            });
        });

        egui::SidePanel::left("jvms").resizable(true).default_width(300.0).show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.strong("Running JVMs");
                if ui.button("Refresh").on_hover_text("Refresh now").clicked() {
                    self.refresh();
                }
                let has_sel = self.selected.is_some();
                if ui
                    .add_enabled(has_sel, egui::Button::new("Details"))
                    .on_hover_text("Minecraft version, mod loader, game directory and full command line")
                    .on_disabled_hover_text("select a JVM first")
                    .clicked()
                {
                    self.show_details = true;
                }
            });
            ui.separator();
            egui::ScrollArea::vertical().show(ui, |ui| {
                let jvms = self.jvms.clone();
                for j in &jvms {
                    let label = format!(
                        "{}{}  ·  {}",
                        j.pid,
                        if j.is_minecraft { " [MC]" } else { "" },
                        short_main(&j.main, &j.cmdline)
                    );
                    let sel = self.selected == Some(j.pid);
                    if ui.selectable_label(sel, label).on_hover_text(&j.cmdline).clicked() {
                        self.selected = Some(j.pid);
                    }
                }
                if jvms.is_empty() {
                    ui.weak("no attachable JVMs");
                }
            });
        });

        self.details_window(ctx);

        egui::CentralPanel::default().show(ctx, |ui| {
            egui::CollapsingHeader::new("① Build agent").default_open(true).show(ui, |ui| {
                ui.weak("Drag a mod jar anywhere on this window, or:");
                path_row(ui, "Payload mod jar", &mut self.payload, &["jar"]);
                if let Some(info) = &self.payload_info {
                    match info {
                        Ok(m) => {
                            egui::Frame::group(ui.style()).show(ui, |ui| {
                                ui.horizontal_wrapped(|ui| {
                                    ui.strong(&m.name);
                                    ui.weak(format!("v{}", m.version));
                                    ui.separator();
                                    ui.label(format!("id: {}", m.id));
                                });
                                ui.horizontal_wrapped(|ui| {
                                    if let Some(mc) = &m.mc { ui.label(format!("MC: {mc}")); }
                                    if let Some(l) = &m.loader { ui.label(format!("· loader: {l}")); }
                                    ui.label(format!("· {} mixin config(s)", m.mixins.len()));
                                    if let Some(aw) = &m.access_widener { ui.label(format!("· AW: {aw}")); }
                                });
                                let ep = m.entry_client.len() + m.entry_main.len();
                                if m.needs_bootstrap_config() {
                                    ui.colored_label(egui::Color32::from_rgb(220, 170, 60), "no entrypoint — needs a bootstrap config");
                                } else {
                                    ui.weak(format!("{ep} entrypoint(s)"));
                                }
                                if !m.other_deps.is_empty() {
                                    ui.weak(format!("depends: {}", m.other_deps.iter().map(|(k, _)| k.clone()).collect::<Vec<_>>().join(", ")));
                                }
                            });
                        }
                        Err(e) => {
                            ui.colored_label(egui::Color32::LIGHT_RED, format!("Not a Fabric mod jar: {e}"));
                        }
                    }
                }
                ui.horizontal(|ui| {
                    ui.label("Agent name");
                    ui.text_edit_singleline(&mut self.agent_name);
                });
                // Build method: Gradle (needs a JDK + repo) or the in-process no-JDK packer (needs the base jar).
                ui.horizontal(|ui| {
                    ui.label("Build method");
                    ui.radio_value(&mut self.build_mode, BuildMode::Gradle, "Gradle (JDK)")
                        .on_hover_text("Drive Gradle's agentJar task — needs a JDK and the mc-graft repo checkout.");
                    ui.radio_value(&mut self.build_mode, BuildMode::NoJdk, "Quick pack (no JDK)")
                        .on_hover_text("Splice the payload into the precompiled base jar in-process — no JDK, no Gradle.");
                });
                if self.build_mode == BuildMode::NoJdk {
                    path_row(ui, "Base jar", &mut self.base_jar, &["jar"]);
                    if !PathBuf::from(self.base_jar.trim()).is_file() {
                        ui.colored_label(egui::Color32::from_rgb(220, 170, 60),
                            "base jar not found — build once with ./gradlew agentBase (JDK), then packing is JDK-free");
                    }
                    // Payload patches: applied to the mod jar before packing (overlay / delete / rename / JSON merge).
                    egui::CollapsingHeader::new(format!("Patches ({})", self.patches.len()))
                        .default_open(!self.patches.is_empty())
                        .show(ui, |ui| {
                            ui.horizontal(|ui| {
                                if ui.button("Add bundle/zip/.j…").clicked() {
                                    if let Some(f) = rfd::FileDialog::new()
                                        .add_filter("patch", &["mcpatch", "zip", "jar", "j"]).pick_file()
                                    {
                                        self.patches.push(f.display().to_string());
                                    }
                                }
                                if ui.button("Add folder…").clicked() {
                                    if let Some(d) = rfd::FileDialog::new().pick_folder() {
                                        self.patches.push(d.display().to_string());
                                    }
                                }
                                ui.checkbox(&mut self.no_verify_base, "No base guard")
                                    .on_hover_text("Ignore each patch's [target] sha256/mod-id base guard.");
                            });
                            let mut remove: Option<usize> = None;
                            for (i, p) in self.patches.iter().enumerate() {
                                ui.horizontal(|ui| {
                                    if ui.small_button("✕").on_hover_text("remove").clicked() {
                                        remove = Some(i);
                                    }
                                    ui.label(egui::RichText::new(p).monospace());
                                });
                            }
                            if let Some(i) = remove {
                                self.patches.remove(i);
                            }
                            if self.patches.is_empty() {
                                ui.weak("Applied to the payload jar before packing, in order: overlay / delete / rename / JSON merge.");
                            }
                        });
                }
                // Config preset (bundled examples/* + load-from-file) — pre-fills the Advanced fields below.
                ui.horizontal(|ui| {
                    ui.label("Config preset");
                    let cur = self.preset.clone();
                    let mut load: Option<(String, config::AgentConfig)> = None;
                    egui::ComboBox::from_id_source("preset").selected_text(&cur).show_ui(ui, |ui| {
                        if ui.selectable_label(cur == "None", "None").clicked() {
                            load = Some(("None".into(), config::AgentConfig::default()));
                        }
                        if !self.presets.is_empty() {
                            ui.separator();
                            ui.weak("bundled");
                        }
                        for p in &self.presets {
                            if ui.selectable_label(cur == p.name, &p.name).clicked() {
                                if let Ok(t) = std::fs::read_to_string(&p.path) {
                                    load = Some((p.name.clone(), config::AgentConfig::from_properties(&t)));
                                }
                            }
                        }
                        if !self.store.build.is_empty() {
                            ui.separator();
                            ui.weak("saved");
                        }
                        for (name, ac) in &self.store.build {
                            if ui.selectable_label(cur == *name, format!("★ {name}")).clicked() {
                                load = Some((name.clone(), ac.clone()));
                            }
                        }
                    });
                    if ui.button("Load file…").clicked() {
                        if let Some(f) = rfd::FileDialog::new().add_filter("properties", &["properties"]).pick_file() {
                            if let Ok(t) = std::fs::read_to_string(&f) {
                                let n = f.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "file".into());
                                load = Some((n, config::AgentConfig::from_properties(&t)));
                            }
                        }
                    }
                    if let Some((n, c)) = load {
                        self.preset = n;
                        self.cfg = c;
                    }
                    ui.add(egui::TextEdit::singleline(&mut self.build_preset_name).desired_width(110.0).hint_text("save as…"));
                    if ui.button("Save").clicked() {
                        let name = self.build_preset_name.trim().to_string();
                        if !name.is_empty() {
                            self.store.build.insert(name.clone(), self.cfg.clone());
                            let msg = match self.store.save() {
                                Ok(()) => format!("[ok] saved build preset '{name}'"),
                                Err(e) => format!("[error] save preset: {e}"),
                            };
                            self.log.push(msg);
                            self.preset = name;
                            self.build_preset_name.clear();
                        }
                    }
                });
                egui::CollapsingHeader::new("Advanced options (all config keys)").default_open(false).show(ui, |ui| {
                    advanced_config(ui, &mut self.cfg);
                });
                ui.add_space(4.0);
                if ui.add_enabled(!self.busy, egui::Button::new("Build agent")).clicked() {
                    self.start_build(ctx);
                }
            });

            ui.add_space(6.0);
            egui::CollapsingHeader::new("② Inject").default_open(true).show(ui, |ui| {
                path_row(ui, "Agent jar", &mut self.agent_jar, &["jar"]);
                ui.horizontal(|ui| {
                    ui.label("Data dir:");
                    ui.radio_value(&mut self.game_dir, GameDir::ClientDir, "Client game dir");
                    ui.radio_value(&mut self.game_dir, GameDir::WorkingDir, "Working dir");
                    ui.radio_value(&mut self.game_dir, GameDir::Custom, "Custom");
                });
                if self.game_dir == GameDir::Custom {
                    path_row(ui, "Custom data dir", &mut self.custom_dir, &[]);
                }
                ui.checkbox(&mut self.tolerate, "Tolerate other-mod mixin failures (modded loaders)");
                // Inject presets: save/apply the data-dir mode + options (+ agent jar).
                ui.horizontal(|ui| {
                    ui.label("Inject preset");
                    let cur = self.inject_preset.clone();
                    let mut apply: Option<(String, InjectConfig)> = None;
                    egui::ComboBox::from_id_source("inject_preset")
                        .selected_text(if cur.is_empty() { "—" } else { &cur })
                        .show_ui(ui, |ui| {
                            if self.store.inject.is_empty() {
                                ui.weak("(none saved)");
                            }
                            for (name, ic) in &self.store.inject {
                                if ui.selectable_label(cur == *name, name).clicked() {
                                    apply = Some((name.clone(), ic.clone()));
                                }
                            }
                        });
                    if let Some((n, ic)) = apply {
                        self.inject_preset = n;
                        self.game_dir = ic.game_dir;
                        self.custom_dir = ic.custom_dir;
                        self.tolerate = ic.tolerate;
                        if !ic.agent_jar.is_empty() {
                            self.agent_jar = ic.agent_jar;
                        }
                    }
                    ui.add(egui::TextEdit::singleline(&mut self.inject_preset_name).desired_width(110.0).hint_text("save as…"));
                    if ui.button("Save").clicked() {
                        let name = self.inject_preset_name.trim().to_string();
                        if !name.is_empty() {
                            let ic = InjectConfig {
                                game_dir: self.game_dir,
                                custom_dir: self.custom_dir.clone(),
                                tolerate: self.tolerate,
                                agent_jar: self.agent_jar.clone(),
                            };
                            self.store.inject.insert(name.clone(), ic);
                            let msg = match self.store.save() {
                                Ok(()) => format!("[ok] saved inject preset '{name}'"),
                                Err(e) => format!("[error] save preset: {e}"),
                            };
                            self.log.push(msg);
                            self.inject_preset = name;
                            self.inject_preset_name.clear();
                        }
                    }
                });
                ui.add_space(4.0);
                let target = self.selected.map(|p| p.to_string()).unwrap_or_else(|| "none".into());
                let can = !self.busy && self.selected.is_some() && PathBuf::from(self.agent_jar.trim()).is_file();
                if ui
                    .add_enabled(can, egui::Button::new(format!("Inject -> PID {target}")))
                    .clicked()
                {
                    self.start_inject(ctx);
                }
            });

            ui.add_space(8.0);
            ui.separator();
            ui.horizontal(|ui| {
                ui.strong("Log");
                if ui.button("clear").clicked() {
                    self.log.clear();
                }
            });
            egui::ScrollArea::vertical().stick_to_bottom(true).auto_shrink([false, false]).show(ui, |ui| {
                for l in &self.log {
                    ui.label(egui::RichText::new(l).monospace().size(12.0));
                }
            });
        });

        // If the payload path changed via Browse/typing (not a drop, which already re-parsed), refresh the mod info.
        if self.payload.trim() != self.info_for.trim() {
            let p = self.payload.trim().to_string();
            if std::path::Path::new(&p).is_file() {
                self.set_payload(PathBuf::from(p));
            } else {
                self.info_for = self.payload.clone();
                self.payload_info = None;
            }
        }
    }
}

/// Tail `path` line-by-line into the channel until a terminal marker or a timeout.
fn tail_log(path: &std::path::Path, tx: &Sender<Msg>, ctx: &egui::Context) {
    use std::io::{BufRead, BufReader};
    let appear = Instant::now() + Duration::from_secs(10);
    while !path.exists() && Instant::now() < appear {
        std::thread::sleep(Duration::from_millis(100));
    }
    let Ok(f) = std::fs::File::open(path) else { return };
    let mut r = BufReader::new(f);
    let deadline = Instant::now() + Duration::from_secs(300);
    let mut line = String::new();
    loop {
        line.clear();
        match r.read_line(&mut line) {
            Ok(0) => {
                if Instant::now() > deadline {
                    return;
                }
                std::thread::sleep(Duration::from_millis(150));
            }
            Ok(_) => {
                let clean = crate::strip_ansi(line.trim_end());
                let terminal = crate::is_terminal_marker(&clean);
                let _ = tx.send(Msg::Line(clean));
                ctx.request_repaint();
                if terminal {
                    return;
                }
            }
            Err(_) => return,
        }
    }
}

/// Render every agent config key as a labeled field, grouped. Mirrors the engine's graft.properties surface.
fn advanced_config(ui: &mut egui::Ui, c: &mut config::AgentConfig) {
    fn field(ui: &mut egui::Ui, label: &str, hint: &str, v: &mut String) {
        ui.horizontal(|ui| {
            ui.add_sized([220.0, 18.0], egui::Label::new(label).truncate());
            ui.add(egui::TextEdit::singleline(v).desired_width(f32::INFINITY).hint_text(hint));
        });
    }
    ui.label(egui::RichText::new("Dependencies & packaging").strong());
    field(ui, "payload.dependencies", "maven coords, comma-separated", &mut c.deps);
    field(ui, "bootstrap.applyDependencyMixins", "\"\" off · \"*\" all · else config-name filter e.g. networking", &mut c.apply_dep_mixins);
    field(ui, "bootstrap.depCallerRewrite.packages", "internal-name prefixes to caller-rewrite (default net/fabricmc/fabric)", &mut c.dep_caller_rewrite_packages);
    field(ui, "payload.mixins.neoforge", "NeoForge mixin config set", &mut c.neoforge_mixins);
    field(ui, "payload.packages", "internal-name prefixes (fat jars)", &mut c.packages);

    ui.add_space(4.0);
    ui.label(egui::RichText::new("Registry / reload lifecycle").strong());
    ui.checkbox(&mut c.reload_resources, "bootstrap.reloadResources (drive a resource reload on attach)");
    field(ui, "bootstrap.thawRegistries", "e.g. minecraft:sound_event", &mut c.thaw_registries);
    ui.checkbox(&mut c.register_mod_container, "bootstrap.registerModContainer (Fabric self-metadata)");
    ui.checkbox(&mut c.reopen_key_bindings, "bootstrap.reopenKeyBindings (allow late fabric-api keybind registration)");
    ui.checkbox(&mut c.bridge_payload_codecs, "bootstrap.bridgePayloadCodecs (wire fabric-api custom packets into MC's live codec)");
    field(ui, "bootstrap.gameDirProperty", "e.g. vspike.gameDir", &mut c.game_dir_property);
    field(ui, "bootstrap.joinGate.screen", "e.g. net/minecraft/.../ConnectScreen", &mut c.join_gate_screen);

    ui.add_space(4.0);
    ui.label(egui::RichText::new("Event bootstrap (mods with no entrypoint)").strong());
    field(ui, "bootstrap.event.manager", "EventManager class", &mut c.evt_manager);
    field(ui, "bootstrap.event.managerInstance", "default INSTANCE", &mut c.evt_manager_instance);
    field(ui, "bootstrap.event.base", "Event base type", &mut c.evt_base);
    field(ui, "bootstrap.event.start", "client-start event class", &mut c.evt_start);
    field(ui, "bootstrap.event.startInstance", "default INSTANCE", &mut c.evt_start_instance);
    field(ui, "bootstrap.event.call", "default callEvent", &mut c.evt_call);

    ui.add_space(4.0);
    ui.label(egui::RichText::new("Readiness probe (async init)").strong());
    field(ui, "bootstrap.ready.class", "class exposing the ready flag", &mut c.ready_class);
    field(ui, "bootstrap.ready.instance", "default INSTANCE", &mut c.ready_instance);
    field(ui, "bootstrap.ready.method", "default isInitialized", &mut c.ready_method);
}

fn opt_str(s: &str) -> Option<String> {
    let t = s.trim();
    (!t.is_empty()).then(|| t.to_string())
}

fn short_main(main: &str, cmdline: &str) -> String {
    let s = if main == "?" { cmdline } else { main };
    match s.rsplit_once(['.', '/']) {
        Some((_, tail)) if !tail.is_empty() => tail.to_string(),
        _ => s.chars().take(40).collect(),
    }
}

/// A labeled path field + a native "Browse…" button.
fn path_row(ui: &mut egui::Ui, label: &str, value: &mut String, exts: &[&str]) {
    ui.horizontal(|ui| {
        ui.label(label);
        ui.add(egui::TextEdit::singleline(value).desired_width(f32::INFINITY).hint_text("path…"));
        if ui.button("Browse…").clicked() {
            let mut dlg = rfd::FileDialog::new();
            if !exts.is_empty() {
                dlg = dlg.add_filter("files", exts);
            }
            let picked = if exts.is_empty() { dlg.pick_folder() } else { dlg.pick_file() };
            if let Some(p) = picked {
                *value = p.display().to_string();
            }
        }
    });
}
