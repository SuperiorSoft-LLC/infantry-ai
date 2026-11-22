// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! TLS for the runner link (RR-3): ONE self-signed EC P-256 certificate per runner, kept in the link dir; the orchestrator
//! trusts no CA and checks no host name, it accepts exactly the leaf whose SHA-256 equals the pin from the bearer
//! (`irb1.<name>.<fp>.<secret>`, byte-compatible with Kotlin's `LinkTls.Pin`: lower-case hex of the DER's SHA-256). The
//! pin is checked inside the TLS handshake, so a wrong certificate is refused before a single HTTP byte (the credential
//! header) is written. rustls runs on the `ring` provider and rcgen makes the certificate: both build on Windows with no
//! system library.

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{ring, CryptoProvider};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::{ClientConfig, ClientConnection, DigitallySignedStruct, ServerConfig, ServerConnection, SignatureScheme, StreamOwned};
use sha2::{Digest, Sha256};
use std::net::TcpStream;
use std::path::Path;
use std::sync::{Arc, Mutex};

const CERT_FILE: &str = "tls-cert.der";
const KEY_FILE: &str = "tls-key.der";
/// The name in the certificate and in the SNI; nothing checks it (the pin is the identity).
const LINK_NAME: &str = "infantry-runner";

/// Lower-case hex SHA-256 of `der`: the pin form inside the bearer.
pub fn fingerprint_of(der: &[u8]) -> String {
    Sha256::digest(der).iter().map(|b| format!("{b:02x}")).collect()
}

/// The runner's certificate and key. The key is never printed (`Debug` redacts it).
#[derive(Clone)]
pub struct RunnerCert {
    cert_der: Vec<u8>,
    key_der: Vec<u8>,
    pub fingerprint: String,
}

impl std::fmt::Debug for RunnerCert {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "RunnerCert(fingerprint={}, key=redacted)", self.fingerprint)
    }
}

impl RunnerCert {
    /// Reads the certificate kept in `dir`, or makes one on first start. The same files give the same fingerprint after a
    /// restart, so no orchestrator re-pairs.
    pub fn load_or_create(dir: &Path) -> std::io::Result<RunnerCert> {
        let (cp, kp) = (dir.join(CERT_FILE), dir.join(KEY_FILE));
        if let (Ok(cert_der), Ok(key_der)) = (std::fs::read(&cp), std::fs::read(&kp)) {
            if !cert_der.is_empty() && !key_der.is_empty() {
                let fingerprint = fingerprint_of(&cert_der);
                return Ok(RunnerCert { cert_der, key_der, fingerprint });
            }
        }
        std::fs::create_dir_all(dir)?;
        let other = |e: rcgen::Error| std::io::Error::new(std::io::ErrorKind::Other, format!("cannot make the runner certificate: {e}"));
        let key = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).map_err(other)?;
        let params = rcgen::CertificateParams::new(vec![LINK_NAME.to_string()]).map_err(other)?;
        let cert = params.self_signed(&key).map_err(other)?;
        let (cert_der, key_der) = (cert.der().to_vec(), key.serialize_der());
        write_private(&kp, &key_der)?;
        write_private(&cp, &cert_der)?;
        let fingerprint = fingerprint_of(&cert_der);
        Ok(RunnerCert { cert_der, key_der, fingerprint })
    }

    /// The server config for the runner's listener.
    pub fn server_config(&self) -> Result<Arc<ServerConfig>, String> {
        let cfg = ServerConfig::builder_with_provider(provider())
            .with_safe_default_protocol_versions()
            .map_err(|e| e.to_string())?
            .with_no_client_auth()
            .with_single_cert(vec![CertificateDer::from(self.cert_der.clone())], PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.key_der.clone())))
            .map_err(|e| format!("the runner certificate is unusable: {e}"))?;
        Ok(Arc::new(cfg))
    }
}

fn write_private(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, bytes)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600))?;
    }
    std::fs::rename(&tmp, path)
}

fn provider() -> Arc<CryptoProvider> {
    Arc::new(ring::default_provider())
}

/// Accepts only the leaf whose SHA-256 is the pin. No chain, no host name, no expiry: the pin IS the trust.
#[derive(Debug)]
struct PinVerifier {
    pin: String,
    provider: Arc<CryptoProvider>,
    seen: Arc<Mutex<Option<String>>>,
}

impl ServerCertVerifier for PinVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let fp = fingerprint_of(end_entity.as_ref());
        *self.seen.lock().unwrap_or_else(|e| e.into_inner()) = Some(fp.clone());
        if fp == self.pin {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General("certificate pin mismatch".into()))
        }
    }

    fn verify_tls12_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn verify_tls13_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

/// The wording of a refused pin: the two causes the owner can act on.
pub fn mismatch_text(pinned: &str, presented: &str) -> String {
    let short = |s: &str| s.chars().take(12).collect::<String>();
    format!(
        "the runner's certificate is not the pinned one (pinned {}..., presented {}...): the runner was recreated without its volume, or this is the wrong runner. Nothing was sent. Add the runner again with its current bearer",
        short(pinned),
        short(presented)
    )
}

/// TLS client over `stream`, handshake finished (and the pin checked) before this returns: the caller writes the HTTP
/// upgrade, credential header and all, only afterwards.
pub fn connect(stream: TcpStream, pin: &str) -> Result<StreamOwned<ClientConnection, TcpStream>, String> {
    let pin = pin.trim().to_ascii_lowercase();
    let seen = Arc::new(Mutex::new(None));
    let provider = provider();
    let cfg = ClientConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()
        .map_err(|e| e.to_string())?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinVerifier { pin: pin.clone(), provider, seen: seen.clone() }))
        .with_no_client_auth();
    let name = ServerName::try_from(LINK_NAME).map_err(|e| e.to_string())?;
    let mut conn = ClientConnection::new(Arc::new(cfg), name).map_err(|e| e.to_string())?;
    let mut stream = stream;
    while conn.is_handshaking() {
        if let Err(e) = conn.complete_io(&mut stream) {
            let presented = seen.lock().unwrap_or_else(|e| e.into_inner()).clone();
            return Err(match presented {
                Some(p) if p != pin => mismatch_text(&pin, &p),
                _ => format!("the TLS handshake with the runner failed: {e}"),
            });
        }
    }
    Ok(StreamOwned::new(conn, stream))
}

/// TLS server over an accepted `stream`; the handshake runs on the first read.
pub fn accept(cfg: &Arc<ServerConfig>, stream: TcpStream) -> Result<StreamOwned<ServerConnection, TcpStream>, String> {
    let conn = ServerConnection::new(cfg.clone()).map_err(|e| e.to_string())?;
    Ok(StreamOwned::new(conn, stream))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp() -> std::path::PathBuf {
        std::env::temp_dir().join(format!("rr3-tls-{}", uuid::Uuid::new_v4()))
    }

    #[test]
    fn the_certificate_survives_a_restart() {
        let d = tmp();
        let a = RunnerCert::load_or_create(&d).unwrap();
        let b = RunnerCert::load_or_create(&d).unwrap();
        assert_eq!(a.fingerprint, b.fingerprint);
        assert_eq!(a.fingerprint.len(), 64);
        assert!(a.fingerprint.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c)));
        assert!(!format!("{a:?}").contains(&format!("{:?}", a.key_der)));
        assert_ne!(RunnerCert::load_or_create(&tmp()).unwrap().fingerprint, a.fingerprint);
        let _ = std::fs::remove_dir_all(d);
    }
}
