//! Native graphical UI for mc-graft (egui/eframe), over the same core as the CLI: discover JVMs, build an agent for a
//! payload, and inject it into a running Minecraft — with a live log. Long operations run on background threads and
//! stream lines back through a channel so the window never blocks.

use crate::{attach, build, modinfo, procs};
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

#[derive(PartialEq, Clone, Copy)]
enum GameDir {
    ClientDir,
    WorkingDir,
    Custom,
}

struct App {
    // discovery
    jvms: Vec<procs::Jvm>,
    selected: Option<i32>,
    last_refresh: Instant,
    // build inputs
    repo: Option<PathBuf>,
    payload: String,
    payload_info: Option<Result<modinfo::ModInfo, String>>,
    info_for: String,
    config: String,
    agent_name: String,
    deps: String,
    // inject inputs
    agent_jar: String,
    game_dir: GameDir,
    custom_dir: String,
    tolerate: bool,
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
        let mut app = App {
            jvms: Vec::new(),
            selected: None,
            last_refresh: Instant::now() - Duration::from_secs(10),
            repo,
            payload: String::new(),
            payload_info: None,
            info_for: String::new(),
            config: String::new(),
            agent_name: "mc-graft-agent.jar".into(),
            deps: String::new(),
            agent_jar: String::new(),
            game_dir: GameDir::ClientDir,
            custom_dir: String::new(),
            tolerate: false,
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
                // A mod with no entrypoints needs a bootstrap override; use a bundled example if one matches its id.
                if m.needs_bootstrap_config() && self.config.trim().is_empty() {
                    if let Some(repo) = &self.repo {
                        let ex = repo.join(format!("examples/{}/bootstrap.properties", m.id));
                        if ex.is_file() {
                            self.config = ex.display().to_string();
                            self.log_line(format!("[info] {} has no entrypoint — using bundled config {}", m.id, ex.display()));
                        } else {
                            self.log_line(format!("[warn] {} has no fabric entrypoint; it likely needs a bootstrap config (none bundled for this id)", m.id));
                        }
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
        let Some(repo) = self.repo.clone() else {
            self.log_line("[warn] no mc-graft repo root found (set it via the CLI --repo, or run from the repo)");
            return;
        };
        if self.payload.trim().is_empty() {
            self.log_line("[warn] pick a payload mod jar first");
            return;
        }
        self.busy = true;
        self.status = "Building agent…".into();
        self.log_line("---- build ----");
        let req = build::BuildRequest {
            payload: PathBuf::from(self.payload.trim()),
            bootstrap_config: opt_path(&self.config),
            mixin_targets: None,
            payload_deps: opt_str(&self.deps),
            agent_name: opt_str(&self.agent_name),
        };
        let tx = self.tx.clone();
        let ctx = ctx.clone();
        std::thread::spawn(move || {
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
        std::thread::spawn(move || {
            match attach::load_agent(pid, &agent, &agent_args) {
                Ok(r) if r.jvm_status == 0 => {
                    let _ = tx.send(Msg::Line(match r.agent_rc {
                        Some(0) => "agent delivered (Agent_OnAttach=0)".into(),
                        Some(rc) => format!("agent delivered, Agent_OnAttach={rc} (agentmain failed — see log)"),
                        None => "agent load delivered (no return code reported)".into(),
                    }));
                    ctx.request_repaint();
                    tail_log(&log_path, &tx, &ctx);
                    let _ = tx.send(Msg::InjectDone(Ok(())));
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
                path_row(ui, "Bootstrap config (optional)", &mut self.config, &["properties"]);
                ui.horizontal(|ui| {
                    ui.label("Agent name");
                    ui.text_edit_singleline(&mut self.agent_name);
                });
                ui.horizontal(|ui| {
                    ui.label("Extra deps (maven coords, optional)");
                    ui.text_edit_singleline(&mut self.deps);
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

fn opt_path(s: &str) -> Option<PathBuf> {
    let t = s.trim();
    (!t.is_empty()).then(|| PathBuf::from(t))
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
