mod build_support;

#[cfg(feature = "runtime")]
mod src;

#[cfg(feature = "runtime")]
use build_support::in_process as implementation;
#[cfg(not(feature = "runtime"))]
use build_support::openssl_linkage;
#[cfg(feature = "runtime")]
#[allow(dead_code)]
pub(crate) use src::platform_dirs;

fn main() {
    println!("cargo::rustc-check-cfg=cfg(copilot_cli_host)");
    println!("cargo::rerun-if-env-changed=COPILOT_REPOSITORY_CLI_HOST");
    if std::env::var("COPILOT_REPOSITORY_CLI_HOST").as_deref() == Ok("1") {
        println!("cargo::rustc-cfg=copilot_cli_host");
    }
    #[cfg(feature = "runtime")]
    implementation::main();
    #[cfg(not(feature = "runtime"))]
    {
        openssl_linkage::require_target_provider(false);
        println!("cargo::rustc-check-cfg=cfg(has_bundled_cli)");
        println!("cargo::rustc-check-cfg=cfg(has_extracted_cli)");
        println!("cargo::rustc-check-cfg=cfg(sdk_compiled_protocol)");
    }
}
