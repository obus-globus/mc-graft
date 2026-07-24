//! In-place bytecode edits for `[[patch]] format = "ops"` — a small, base-robust structured patch DSL over
//! `ristretto_classfile`. Each op replaces exactly one instruction in place (1→1), which ristretto handles safely at
//! any byte size: it stores branch targets, exception ranges, line numbers and StackMapTable frame deltas as *logical
//! instruction offsets* and recomputes every byte offset on `to_bytes`, so replacing an instruction never shifts an
//! index or invalidates a frame. This sidesteps the round-trip fragility of the Krakatau-diff approach.
//!
//! v1 ops (all stack-neutral, 1→1 replacements):
//!
//! - `replace_const` — swap an int or String constant load (any encoding) for another.
//! - `redirect_invoke` — retarget a method call to a different owner/name (descriptor preserved -> stack-safe).
//!
//! Stack-changing ops (insert/remove/set_return) would need frame-index adjustment and are intentionally not here yet.

use ristretto_classfile::attributes::{Attribute, Instruction};
use ristretto_classfile::{ClassFile, ConstantPool};

pub struct Op {
    /// Restrict to a method by `(name, descriptor)`; `None` = every method in the class.
    pub in_method: Option<(String, String)>,
    pub kind: OpKind,
}

pub enum OpKind {
    ReplaceConstInt { from: i32, to: i32 },
    ReplaceConstStr { from: String, to: String },
    /// `(owner, name, desc)` call site retargeted to `(owner, name)` — descriptor is preserved for stack safety.
    RedirectInvoke { from: (String, String, String), to: (String, String) },
}

/// Apply `ops` to a class image, returning the rewritten bytes. Errors if a class won't parse, or if no op matched any
/// site (a patch that silently does nothing is treated as a mistake).
pub fn apply_ops(class_bytes: &[u8], ops: &[Op]) -> Result<Vec<u8>, String> {
    let mut cf = ClassFile::from_bytes(class_bytes).map_err(|e| format!("parse class: {e}"))?;
    let cp = &mut cf.constant_pool; // disjoint field borrow from cf.methods below
    let mut total = 0usize;
    for m in &mut cf.methods {
        let mname = cp.try_get_utf8(m.name_index).map_err(|e| e.to_string())?.to_string();
        let mdesc = cp.try_get_utf8(m.descriptor_index).map_err(|e| e.to_string())?.to_string();
        for attr in &mut m.attributes {
            let Attribute::Code { code, .. } = attr else { continue };
            for op in ops {
                if let Some((n, d)) = &op.in_method {
                    if *n != mname || *d != mdesc {
                        continue;
                    }
                }
                total += apply_op(code, &op.kind, cp)?;
            }
        }
    }
    if total == 0 {
        return Err("no matching site found — the ops patched nothing (check class/method/from values)".into());
    }
    let mut out = Vec::new();
    cf.to_bytes(&mut out).map_err(|e| format!("serialize class: {e}"))?;
    Ok(out)
}

fn apply_op(code: &mut [Instruction], kind: &OpKind, cp: &mut ConstantPool) -> Result<usize, String> {
    let mut n = 0usize;
    match kind {
        OpKind::ReplaceConstInt { from, to } => {
            for ins in code.iter_mut() {
                if int_value(ins, cp) == Some(*from) {
                    *ins = int_push(*to, cp)?;
                    n += 1;
                }
            }
        }
        OpKind::ReplaceConstStr { from, to } => {
            for ins in code.iter_mut() {
                let idx = match &*ins {
                    Instruction::Ldc(x) => Some(u16::from(*x)),
                    Instruction::Ldc_w(x) => Some(*x),
                    _ => None,
                };
                let Some(ci) = idx else { continue };
                if cp.try_get_string(ci).map(|s| s == from.as_str()).unwrap_or(false) {
                    let ni = cp.add_string(to).map_err(|e| e.to_string())?;
                    *ins = if ni <= u16::from(u8::MAX) { Instruction::Ldc(ni as u8) } else { Instruction::Ldc_w(ni) };
                    n += 1;
                }
            }
        }
        OpKind::RedirectInvoke { from, to } => {
            for ins in code.iter_mut() {
                let (idx, iface) = match &*ins {
                    Instruction::Invokevirtual(x) | Instruction::Invokestatic(x) | Instruction::Invokespecial(x) => (Some(*x), false),
                    Instruction::Invokeinterface(x, _) => (Some(*x), true),
                    _ => (None, false),
                };
                let Some(ci) = idx else { continue };
                let Some((o, nm, d)) = resolve_ref(cp, ci, iface) else { continue };
                if o == from.0 && nm == from.1 && d == from.2 {
                    let cls = cp.add_class(&to.0).map_err(|e| e.to_string())?;
                    let ni = if iface {
                        cp.add_interface_method_ref(cls, &to.1, &from.2).map_err(|e| e.to_string())?
                    } else {
                        cp.add_method_ref(cls, &to.1, &from.2).map_err(|e| e.to_string())?
                    };
                    match &mut *ins {
                        Instruction::Invokevirtual(x) | Instruction::Invokestatic(x) | Instruction::Invokespecial(x) => *x = ni,
                        Instruction::Invokeinterface(x, _) => *x = ni,
                        _ => {}
                    }
                    n += 1;
                }
            }
        }
    }
    Ok(n)
}

/// The int value an instruction pushes, if it is an integer push (constant-pool `Ldc` resolved via `cp`).
fn int_value(ins: &Instruction, cp: &ConstantPool) -> Option<i32> {
    match ins {
        Instruction::Iconst_m1 => Some(-1),
        Instruction::Iconst_0 => Some(0),
        Instruction::Iconst_1 => Some(1),
        Instruction::Iconst_2 => Some(2),
        Instruction::Iconst_3 => Some(3),
        Instruction::Iconst_4 => Some(4),
        Instruction::Iconst_5 => Some(5),
        Instruction::Bipush(v) => Some(i32::from(*v)),
        Instruction::Sipush(v) => Some(i32::from(*v)),
        Instruction::Ldc(i) => cp.try_get_integer(u16::from(*i)).ok().copied(),
        Instruction::Ldc_w(i) => cp.try_get_integer(*i).ok().copied(),
        _ => None,
    }
}

/// The narrowest instruction that pushes `v` (adds an Integer constant only when it exceeds the immediate forms).
fn int_push(v: i32, cp: &mut ConstantPool) -> Result<Instruction, String> {
    Ok(match v {
        -1 => Instruction::Iconst_m1,
        0 => Instruction::Iconst_0,
        1 => Instruction::Iconst_1,
        2 => Instruction::Iconst_2,
        3 => Instruction::Iconst_3,
        4 => Instruction::Iconst_4,
        5 => Instruction::Iconst_5,
        _ if (i32::from(i8::MIN)..=i32::from(i8::MAX)).contains(&v) => Instruction::Bipush(v as i8),
        _ if (i32::from(i16::MIN)..=i32::from(i16::MAX)).contains(&v) => Instruction::Sipush(v as i16),
        _ => Instruction::Ldc_w(cp.add_integer(v).map_err(|e| e.to_string())?),
    })
}

/// Resolve a method/interface-method ref constant to `(owner-internal-name, name, descriptor)`.
fn resolve_ref(cp: &ConstantPool, index: u16, iface: bool) -> Option<(String, String, String)> {
    let (class_index, nat_index) = if iface {
        cp.try_get_interface_method_ref(index).ok()?
    } else {
        cp.try_get_method_ref(index).ok()?
    };
    let owner = cp.try_get_class(*class_index).ok()?.to_string();
    let (name_index, desc_index) = cp.try_get_name_and_type(*nat_index).ok()?;
    let name = cp.try_get_utf8(*name_index).ok()?.to_string();
    let desc = cp.try_get_utf8(*desc_index).ok()?.to_string();
    Some((owner, name, desc))
}
