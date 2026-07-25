//! In-place bytecode edits for `[[patch]] format = "ops"` — a small, base-robust structured patch DSL over
//! `ristretto_classfile`. Each op replaces exactly one instruction in place (1→1). Instruction *count* is what makes
//! that safe: ristretto stores branch targets, exception ranges, line numbers and StackMapTable frame deltas as
//! *logical instruction offsets* and recomputes every byte offset on `to_bytes`, so a 1→1 replacement never shifts an
//! index. `apply_op` takes `&mut [Instruction]` (a slice, not a Vec) so the type system enforces that.
//!
//! Instruction *byte size*, however, is NOT free — a replacement can widen or narrow the encoding (`iconst_1` →
//! `sipush`, `ldc` → `ldc_w`), and two of ristretto's `Code` sub-attributes mishandle that. See
//! [`sanitize_touched_code`], which every edited method is run through.
//!
//! v1 ops:
//!
//! - `replace_const` — swap an int or String constant load (any encoding) for another.
//! - `redirect_invoke` — retarget a method call to a different owner/name (descriptor preserved -> stack-safe).
//! - `set_return` — replace a whole method body with `return <const>` (the one length-changing op; handled in
//!   [`apply_ops`], not [`apply_op`], and it drops the offset-bearing attributes outright).
//!
//! Ops that insert or remove instructions would need frame-index adjustment and are intentionally not here yet.

use ristretto_classfile::attributes::{Attribute, Instruction, StackFrame};
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
            let mut touched = 0usize;
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
                        touched += 1;
                    }
                    kind => {
                        let n = apply_op(code, kind, cp)?;
                        hits[oi] += n;
                        touched += n;
                    }
                }
            }
            if touched > 0 {
                sanitize_touched_code(attributes);
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

/// Repair the `Code` sub-attributes of a method an op actually edited, so a change in an instruction's *encoded byte
/// width* can't corrupt the class. Both hazards below are invisible until the JVM refuses to load the result.
///
/// 1. **StackMapTable frame-type overflow.** ristretto re-derives frame deltas from the new byte offsets on write, but
///    writes `SameFrame`/`SameLocals1StackItemFrame` back in their compact form — and `u8::try_from` accepts a delta of
///    64..=255, which is out of range for both (JVMS §4.7.4 allows 0..=63 and 64..=127 respectively). An over-range
///    `SameFrame` re-parses as a `SameLocals1StackItemFrame`, which then consumes a `verification_type_info` byte and
///    desynchronizes the whole rest of the table — `ClassFormatError: StackMapTable format error`. Promoting both to
///    their `*Extended` forms, which carry a real `u16 offset_delta`, makes any delta representable. This is
///    information-preserving: `StackFrame::offset_delta()` reports the same logical value for either encoding, and the
///    write path reads exactly that.
///
/// 2. **Stale byte offsets in debug attributes.** Only `LineNumberTable` and `StackMapTable` get remapped on write;
///    `LocalVariableTable`, `LocalVariableTypeTable` and the type-annotation tables keep *raw byte* `start_pc`/`length`
///    values. After a size change those ranges are wrong, and a `start_pc + length` past the new `code_length` is a
///    hard `ClassFormatError`. They are debug info only, so drop them rather than trying to remap. (`set_return`
///    already does this for the same reason.)
fn sanitize_touched_code(attributes: &mut Vec<Attribute>) {
    attributes.retain(|a| {
        !matches!(
            a,
            Attribute::LocalVariableTable { .. }
                | Attribute::LocalVariableTypeTable { .. }
                | Attribute::RuntimeVisibleTypeAnnotations { .. }
                | Attribute::RuntimeInvisibleTypeAnnotations { .. }
        )
    });
    for a in attributes.iter_mut() {
        let Attribute::StackMapTable { frames, .. } = a else { continue };
        for f in frames.iter_mut() {
            match f {
                StackFrame::SameFrame { frame_type } => {
                    let offset_delta = u16::from(*frame_type);
                    *f = StackFrame::SameFrameExtended { frame_type: 251, offset_delta };
                }
                StackFrame::SameLocals1StackItemFrame { frame_type, stack } => {
                    let offset_delta = u16::from(frame_type.saturating_sub(64));
                    let stack = std::mem::take(stack);
                    *f = StackFrame::SameLocals1StackItemFrameExtended { frame_type: 247, offset_delta, stack };
                }
                _ => {}
            }
        }
    }
}

/// Reuse an existing constant-pool entry instead of appending a duplicate.
///
/// `ConstantPool::add` never deduplicates, so without these a class-wide op appends a fresh entry per matched site (up
/// to six for a `redirect_invoke`). That bloats the pool toward its `u16` ceiling, and — because a constant's *index*
/// decides whether a load encodes as `ldc` (2 bytes) or `ldc_w` (3) — it also silently widens instructions, which is
/// what makes the hazards in [`sanitize_touched_code`] fire in the first place.
fn intern_string(cp: &mut ConstantPool, s: &str) -> Result<u16, String> {
    for i in 1..pool_len(cp) {
        if cp.try_get_string(i).map(|v| v == s).unwrap_or(false) {
            return Ok(i);
        }
    }
    cp.add_string(s).map_err(|e| e.to_string())
}

fn intern_integer(cp: &mut ConstantPool, v: i32) -> Result<u16, String> {
    for i in 1..pool_len(cp) {
        if cp.try_get_integer(i).map(|x| *x == v).unwrap_or(false) {
            return Ok(i);
        }
    }
    cp.add_integer(v).map_err(|e| e.to_string())
}

fn intern_class(cp: &mut ConstantPool, name: &str) -> Result<u16, String> {
    for i in 1..pool_len(cp) {
        if cp.try_get_class(i).map(|v| v == name).unwrap_or(false) {
            return Ok(i);
        }
    }
    cp.add_class(name).map_err(|e| e.to_string())
}

fn intern_method_ref(cp: &mut ConstantPool, iface: bool, owner: &str, name: &str, desc: &str) -> Result<u16, String> {
    for i in 1..pool_len(cp) {
        if let Some((was_iface, o, nm, d)) = resolve_ref(cp, i) {
            if was_iface == iface && o == owner && nm == name && d == desc {
                return Ok(i);
            }
        }
    }
    let cls = intern_class(cp, owner)?;
    if iface {
        cp.add_interface_method_ref(cls, name, desc).map_err(|e| e.to_string())
    } else {
        cp.add_method_ref(cls, name, desc).map_err(|e| e.to_string())
    }
}

/// Pool length as a `u16` bound; entries are 1-based, and index 0 is a placeholder.
fn pool_len(cp: &ConstantPool) -> u16 {
    u16::try_from(cp.len()).unwrap_or(u16::MAX)
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
                    let ni = intern_string(cp, to)?;
                    *ins = ldc(ni);
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
                    let ni = intern_method_ref(cp, was_iface, &to.0, &to.1, &from.2)?;
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
        _ => ldc(intern_integer(cp, v)?),
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
                let idx = intern_string(cp, s)?;
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
