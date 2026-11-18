// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! JSON text as kotlinx.serialization 1.10 `Json.encodeToString(JsonObject.serializer(), ...)` writes it. Strings,
//! objects and arrays encode as serde_json writes them; an unquoted literal (a number) does NOT keep its text:
//! `JsonLiteralSerializer` writes it as a Long when it parses as one (`-0` -> `0`), as a ULong when it parses as one, and
//! otherwise as a Double through Java's `Double.toString` (`1.50e3` -> `1500.0`, `1e22` -> `1.0E22`). A Double that is
//! not finite (`1e400`) throws kotlinx's `JsonEncodingException`, whose text this returns as the error.
//! (`JsonElement.toString()` keeps the literal text; this is only for the places Kotlin uses the serializer.)

use serde_json::Value;

/// kotlinx's encoding of [v], or its encoding exception's message.
pub fn encode(v: &Value) -> Result<String, String> {
    let mut out = String::new();
    write(v, &mut out)?;
    Ok(out)
}

fn write(v: &Value, out: &mut String) -> Result<(), String> {
    match v {
        Value::Null => out.push_str("null"),
        Value::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Value::String(s) => out.push_str(&serde_json::to_string(s).unwrap_or_default()),
        Value::Number(n) => number(&n.to_string(), out)?,
        Value::Array(a) => {
            out.push('[');
            for (i, e) in a.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                write(e, out)?;
            }
            out.push(']');
        }
        Value::Object(m) => {
            out.push('{');
            for (i, (k, e)) in m.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                out.push_str(&serde_json::to_string(k).unwrap_or_default());
                out.push(':');
                write(e, out)?;
            }
            out.push('}');
        }
    }
    Ok(())
}

fn number(literal: &str, out: &mut String) -> Result<(), String> {
    if let Ok(l) = literal.parse::<i64>() {
        out.push_str(&l.to_string());
        return Ok(());
    }
    if let Ok(u) = literal.parse::<u64>() {
        out.push_str(&u.to_string());
        return Ok(());
    }
    let Ok(d) = literal.parse::<f64>() else {
        // Not reachable for a literal serde_json accepted; kotlinx would write it as a string.
        out.push_str(&serde_json::to_string(literal).unwrap_or_default());
        return Ok(());
    };
    let text = java_double(d);
    out.push_str(&text);
    if !d.is_finite() {
        return Err(format!(
            "Unexpected special floating-point value {text}. By default, non-finite floating point values are prohibited because they do not conform JSON specification. It is possible to deserialize them using 'JsonBuilder.allowSpecialFloatingPointValues = true'\nCurrent output: {}",
            minify(out)
        ));
    }
    Ok(())
}

/// kotlinx's `CharSequence.minify()` (no offset): under 200 UTF-16 units unchanged, else "....." and the last 60.
fn minify(s: &str) -> String {
    let units: Vec<u16> = s.encode_utf16().collect();
    if units.len() < 200 {
        return s.to_string();
    }
    let tail = &units[units.len() - 60..];
    // A pair cut in half is a lone surrogate, which the JVM's UTF-8 writer turns into '?'.
    let text: String = char::decode_utf16(tail.iter().copied()).map(|c| c.unwrap_or('?')).collect();
    format!(".....{text}")
}

/// Java's `Double.toString` (JDK 19+): the shortest decimal that rounds to [d] (two digits when one would do, the
/// closest such), plain between 10^-3 and 10^7, else `d.dddE±n`.
pub fn java_double(d: f64) -> String {
    if d.is_nan() {
        return "NaN".into();
    }
    if d.is_infinite() {
        return if d > 0.0 { "Infinity" } else { "-Infinity" }.into();
    }
    if d == 0.0 {
        return if d.is_sign_negative() { "-0.0" } else { "0.0" }.into();
    }
    let sign = if d < 0.0 { "-" } else { "" };
    let a = d.abs();
    let mut sci = format!("{a:e}");
    if !sci.split('e').next().unwrap_or("").contains('.') {
        sci = format!("{a:.1e}");
    }
    let (mantissa, exp) = sci.split_once('e').unwrap_or((&sci, "0"));
    let exp: i32 = exp.parse().unwrap_or(0);
    let digits: String = mantissa.chars().filter(|c| c.is_ascii_digit()).collect();
    let digits = {
        let t = digits.trim_end_matches('0');
        if t.is_empty() { "0".to_string() } else { t.to_string() }
    };
    if (-3..7).contains(&exp) {
        if exp >= 0 {
            let int_len = exp as usize + 1;
            let (int, frac) = if digits.len() > int_len {
                (digits[..int_len].to_string(), digits[int_len..].to_string())
            } else {
                (format!("{digits}{}", "0".repeat(int_len - digits.len())), "0".to_string())
            };
            format!("{sign}{int}.{frac}")
        } else {
            format!("{sign}0.{}{digits}", "0".repeat((-exp - 1) as usize))
        }
    } else {
        let frac = if digits.len() > 1 { &digits[1..] } else { "0" };
        format!("{sign}{}.{frac}E{exp}", &digits[..1])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn doubles_like_java() {
        for (d, s) in [
            (1500.0, "1500.0"),
            (1.2345678901234567890123e22, "1.2345678901234568E22"),
            (1e7, "1.0E7"),
            (9999999.0, "9999999.0"),
            (0.001, "0.001"),
            (1e-4, "1.0E-4"),
            (5e-324, "4.9E-324"),
            (-0.0, "-0.0"),
            (100.0, "100.0"),
            (1e23, "1.0E23"),
            (2e23, "2.0E23"),
            (0.1, "0.1"),
            (-12.5, "-12.5"),
            (1.0, "1.0"),
            (123456.789, "123456.789"),
            (f64::MAX, "1.7976931348623157E308"),
        ] {
            assert_eq!(java_double(d), s, "{d:e}");
        }
    }

    #[test]
    fn literals_like_kotlinx() {
        let v: Value = serde_json::from_str(r#"{"a":[1.0,-0,1.50e3,12345678901234567890123,18446744073709551615,-9223372036854775808,"x"],"b":true,"c":null}"#).unwrap();
        assert_eq!(
            encode(&v).unwrap(),
            r#"{"a":[1.0,0,1500.0,1.2345678901234568E22,18446744073709551615,-9223372036854775808,"x"],"b":true,"c":null}"#
        );
    }

    #[test]
    fn non_finite_fails_like_kotlinx() {
        let v: Value = serde_json::from_str(r#"{"name":"t","y":[1.0,-0,1e400]}"#).unwrap();
        assert_eq!(
            encode(&v).unwrap_err(),
            "Unexpected special floating-point value Infinity. By default, non-finite floating point values are prohibited because they do not conform JSON specification. It is possible to deserialize them using 'JsonBuilder.allowSpecialFloatingPointValues = true'\nCurrent output: {\"name\":\"t\",\"y\":[1.0,0,Infinity"
        );
    }
}
