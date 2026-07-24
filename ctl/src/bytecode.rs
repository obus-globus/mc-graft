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
    /// Replace the target method's whole body with `return <value>` (value typed to the method's return descriptor).
    /// `None` = the type's zero/null. Requires `in_method`. Straight-line body -> no stack-map frames needed.
    SetReturn { value: Option<RetVal> },
}

/// A literal for `set_return`, interpreted against the method's return type.
pub enum RetVal {
    Int(i64),
    Float(f64),
    Str(String),
    Null,
}

/// Apply `ops` to a class image, returning the rewritten bytes. Errors if a class won't parse, or if no op matched any
/// site (a patch that silently does nothing is treated as a mistake).
pub fn apply_ops(class_bytes: &[u8], ops: &[Op]) -> Result<Vec<u8>, String> {
    let mut cf = ClassFile::from_bytes(class_bytes).map_err(|e| format!("parse class: {e}"))?;
    let cp = &mut cf.constant_pool; // disjoint field borrow from cf.methods below
    let mut hits = vec![0usize; ops.len()]; // per-op, so a single op matching nothing is still an error
    for m in &mut cf.methods {
        let mname = cp.try_get_utf8(m.name_index).map_err(|e| e.to_string())?.to_string();
        let mdesc = cp.try_get_utf8(m.descriptor_index).map_err(|e| e.to_string())?.to_string();
        let ret = return_type(&mdesc);
        for attr in &mut m.attributes {
            let Attribute::Code { code, max_stack, exception_table, attributes, .. } = attr else { continue };
            for (oi, op) in ops.iter().enumerate() {
                if let Some((n, d)) = &op.in_method {
                    if *n != mname || *d != mdesc {
                        continue;
                    }
                }
                match &op.kind {
                    OpKind::SetReturn { value } => {
                        let (body, ms) = return_body(ret, value, cp)?;
                        *code = body;
                        *max_stack = ms;
                        exception_table.clear();
                        // Offsets in these attributes point into the old body; drop them (the new body needs none).
                        attributes.retain(|a| {
                            !matches!(
                                a,
                                Attribute::StackMapTable { .. }
                                    | Attribute::LineNumberTable { .. }
                                    | Attribute::LocalVariableTable { .. }
                                    | Attribute::LocalVariableTypeTable { .. }
                            )
                        });
                        hits[oi] += 1;
                    }
                    kind => hits[oi] += apply_op(code, kind, cp)?,
                }
            }
        }
    }
    if let Some(oi) = hits.iter().position(|h| *h == 0) {
        return Err(format!("op #{} matched no site — patched nothing (check class/method/`in`/`from`)", oi + 1));
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
                let idx = match &*ins {
                    Instruction::Invokevirtual(x) | Instruction::Invokestatic(x) | Instruction::Invokespecial(x) => Some(*x),
                    Instruction::Invokeinterface(x, _) => Some(*x),
                    _ => None,
                };
                let Some(ci) = idx else { continue };
                // Resolve as either a Methodref or InterfaceMethodref (invokestatic/-special on a Java-8+ interface
                // method uses InterfaceMethodref); preserve that kind when writing the new ref.
                let Some((was_iface, o, nm, d)) = resolve_ref(cp, ci) else { continue };
                if o == from.0 && nm == from.1 && d == from.2 {
                    let cls = cp.add_class(&to.0).map_err(|e| e.to_string())?;
                    let ni = if was_iface {
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
        OpKind::SetReturn { .. } => unreachable!("set_return is applied in apply_ops, not apply_op"),
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

/// Resolve a Methodref or InterfaceMethodref constant to `(is_interface, owner-internal-name, name, descriptor)`.
/// Tries both kinds regardless of the calling opcode, since invokestatic/-special can reference either.
fn resolve_ref(cp: &ConstantPool, index: u16) -> Option<(bool, String, String, String)> {
    let (iface, class_index, nat_index) = match cp.try_get_method_ref(index) {
        Ok((c, n)) => (false, *c, *n),
        Err(_) => {
            let (c, n) = cp.try_get_interface_method_ref(index).ok()?;
            (true, *c, *n)
        }
    };
    let owner = cp.try_get_class(class_index).ok()?.to_string();
    let (name_index, desc_index) = cp.try_get_name_and_type(nat_index).ok()?;
    let name = cp.try_get_utf8(*name_index).ok()?.to_string();
    let desc = cp.try_get_utf8(*desc_index).ok()?.to_string();
    Some((iface, owner, name, desc))
}

/// The return-type descriptor of a method descriptor (`(...)Ret` -> `Ret`).
fn return_type(method_desc: &str) -> &str {
    method_desc.rfind(')').map_or("V", |i| &method_desc[i + 1..])
}

/// Build a straight-line `return <value>` body for a method whose return descriptor is `ret`, plus its max_stack.
fn return_body(ret: &str, value: &Option<RetVal>, cp: &mut ConstantPool) -> Result<(Vec<Instruction>, u16), String> {
    let mut b = Vec::new();
    let ms = match ret.chars().next() {
        Some('V') => {
            if value.is_some() {
                return Err("set_return: a void method takes no value".into());
            }
            b.push(Instruction::Return);
            0
        }
        Some('I' | 'Z' | 'B' | 'S' | 'C') => {
            b.push(int_push(ret_int(value)?, cp)?);
            b.push(Instruction::Ireturn);
            1
        }
        Some('J') => {
            let idx = cp.add_long(ret_long(value)?).map_err(|e| e.to_string())?;
            b.push(Instruction::Ldc2_w(idx));
            b.push(Instruction::Lreturn);
            2
        }
        Some('F') => {
            let idx = cp.add_float(ret_num(value)? as f32).map_err(|e| e.to_string())?;
            b.push(ldc(idx));
            b.push(Instruction::Freturn);
            1
        }
        Some('D') => {
            let idx = cp.add_double(ret_num(value)?).map_err(|e| e.to_string())?;
            b.push(Instruction::Ldc2_w(idx));
            b.push(Instruction::Dreturn);
            2
        }
        Some('L' | '[') => match value {
            None | Some(RetVal::Null) => {
                b.push(Instruction::Aconst_null);
                b.push(Instruction::Areturn);
                1
            }
            Some(RetVal::Str(s)) => {
                if ret != "Ljava/lang/String;" {
                    return Err(format!("set_return: a string value can only be returned as String, not '{ret}'"));
                }
                let idx = cp.add_string(s).map_err(|e| e.to_string())?;
                b.push(ldc(idx));
                b.push(Instruction::Areturn);
                1
            }
            Some(_) => return Err(format!("set_return: a numeric value can't be returned as '{ret}'")),
        },
        _ => return Err(format!("set_return: unsupported return type '{ret}'")),
    };
    Ok((b, ms))
}

fn ret_int(v: &Option<RetVal>) -> Result<i32, String> {
    match v {
        None => Ok(0),
        Some(RetVal::Int(i)) => i32::try_from(*i).map_err(|_| "set_return: int value out of range".to_string()),
        Some(_) => Err("set_return: this return type needs an integer value".into()),
    }
}
fn ret_long(v: &Option<RetVal>) -> Result<i64, String> {
    match v {
        None => Ok(0),
        Some(RetVal::Int(i)) => Ok(*i),
        Some(_) => Err("set_return: a long return needs an integer value".into()),
    }
}
fn ret_num(v: &Option<RetVal>) -> Result<f64, String> {
    match v {
        None => Ok(0.0),
        Some(RetVal::Int(i)) => Ok(*i as f64),
        Some(RetVal::Float(f)) => Ok(*f),
        Some(_) => Err("set_return: this return type needs a numeric value".into()),
    }
}
fn ldc(idx: u16) -> Instruction {
    if idx <= u16::from(u8::MAX) {
        Instruction::Ldc(idx as u8)
    } else {
        Instruction::Ldc_w(idx)
    }
}
