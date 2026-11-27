#![cfg(all(feature = "test-support", feature = "in-process"))]

use copilot_sdk::handler::ApproveAllHandler;
use copilot_sdk::network::{NetworkConfig, NetworkScope};
use copilot_sdk::{
    BUILTIN_TOOLS_ISOLATED, Client, ClientMode, ClientOptions, CreateSessionOptions,
    InProcessRuntimeOptions, InternalTestOptions, OwnedRuntimeOptions, RuntimeConnection,
    RuntimeHostConfiguration, ToolSet,
};
use std::path::PathBuf;
use std::sync::Arc;

use super::support::{DEFAULT_TEST_TOKEN, E2eContext};

const HELPER_TEST: &str = "e2e_cases::inprocess_child_processes::minimal_host_child_process_helper";
const HELPER_ENV: &str = "COPILOT_MINIMAL_HOST_CHILD_PROCESS_HELPER";
const RESULT_ENV: &str = "COPILOT_MINIMAL_HOST_CHILD_PROCESS_RESULT";
const OBSERVE_ONLY_ENV: &str = "COPILOT_MINIMAL_HOST_OBSERVE_ONLY";
const INHERITED_GIT_ENVIRONMENT: [&str; 10] = [
    "GIT_DIR",
    "GIT_WORK_TREE",
    "GIT_COMMON_DIR",
    "GIT_DISCOVERY_ACROSS_FILESYSTEM",
    "GIT_CEILING_DIRECTORIES",
    "GIT_CONFIG_SYSTEM",
    "GIT_CONFIG_GLOBAL",
    "GIT_CONFIG_NOSYSTEM",
    "GIT_CONFIG_COUNT",
    "GIT_CONFIG_PARAMETERS",
];
const SNAPSHOT: &str = "empty_mode_isolated_set_shell_tool_is_not_exposed";
const PROMPT: &str = "Say hi.";

#[cfg(any(target_os = "linux", windows))]
#[tokio::test]
async fn minimal_in_process_host_does_not_spawn_children() {
    for is_repository in [false, true] {
        super::support::with_dedicated_e2e_context("mode_empty", SNAPSHOT, move |context| {
            Box::pin(async move {
                let scenario = if is_repository {
                    "repository"
                } else {
                    "not-repository"
                };
                let working_directory = context.work_dir().join(scenario);
                std::fs::create_dir_all(&working_directory).expect("create scenario directory");
                if is_repository {
                    let git_directory = working_directory.join(".git");
                    // The runtime only recognizes a repository without spawning `git` when
                    // HEAD, objects and refs all exist.
                    for directory in ["objects", "refs"] {
                        std::fs::create_dir_all(git_directory.join(directory))
                            .expect("create repository metadata");
                    }
                    std::fs::write(git_directory.join("HEAD"), "ref: refs/heads/main\n")
                        .expect("write repository HEAD");
                    std::fs::write(
                        git_directory.join("config"),
                        "[core]\n\trepositoryformatversion = 0\n\tbare = false\n\
                         [remote \"origin\"]\n\turl = https://github.com/test-org/test-repo.git\n",
                    )
                    .expect("write repository remote");
                }

                let internal_test_options = context
                    .client_options_with_transport(RuntimeConnection::InProcess(
                        InProcessRuntimeOptions::new(),
                    ))
                    .internal_test_options
                    .expect("replay context options");
                let result_path = context.home_dir().join("child-process-helper-result");
                let git_trace_path = context.home_dir().join("child-process-helper-git-trace");
                let output = run_isolated_helper(
                    context,
                    &working_directory,
                    &result_path,
                    &git_trace_path,
                    internal_test_options.clone(),
                    false,
                )
                .await
                .expect("run isolated helper");
                #[allow(unused_mut)]
                let mut report = format!(
                    "{scenario} helper {}\nstdout:\n{}\nstderr:\n{}\ngit trace:\n{}",
                    output.status,
                    String::from_utf8_lossy(&output.stdout),
                    String::from_utf8_lossy(&output.stderr),
                    read_git_trace(&git_trace_path)
                );
                // The seccomp filter kills the helper before a denied spawn can run, so the
                // spawned program is unknown; rerun with spawns allowed to learn which `git`
                // commands it would have started.
                #[cfg(target_os = "linux")]
                if !output.status.success() {
                    let observed_trace_path = context
                        .home_dir()
                        .join("child-process-helper-observed-git-trace");
                    let observed = run_isolated_helper(
                        context,
                        &working_directory,
                        &result_path,
                        &observed_trace_path,
                        internal_test_options,
                        true,
                    )
                    .await
                    .expect("run observing helper");
                    report.push_str(&format!(
                        "\nobserving rerun {}\nstderr:\n{}\ngit trace:\n{}",
                        observed.status,
                        String::from_utf8_lossy(&observed.stderr),
                        read_git_trace(&observed_trace_path)
                    ));
                }
                assert!(output.status.success(), "{report}");
                let completion = std::fs::read_to_string(result_path).unwrap_or_else(|error| {
                    panic!("read helper completion marker: {error}\n{report}")
                });
                assert_eq!(
                    completion, "completed",
                    "{scenario} helper test did not report completed runtime work\n{report}"
                );
            })
        })
        .await;
    }
}

async fn run_isolated_helper(
    context: &E2eContext,
    working_directory: &std::path::Path,
    result_path: &std::path::Path,
    git_trace_path: &std::path::Path,
    internal_test_options: InternalTestOptions,
    observe_only: bool,
) -> std::io::Result<std::process::Output> {
    let no_proxy = internal_test_options
        .no_proxy
        .as_deref()
        .unwrap_or_default();
    let mut command =
        tokio::process::Command::new(std::env::current_exe().expect("resolve E2E test executable"));
    command
        .args(["--exact", HELPER_TEST, "--nocapture"])
        .env(HELPER_ENV, "1")
        // Indexed search starts for every session and spawns `git rev-parse` and its `tgrep`
        // helper, whatever the session's tools or host-Git setting; a host that wants zero child
        // processes must turn it off.
        .env("USE_TGREP", "false")
        // Loopback requests bypass network callbacks, so disable OS proxy discovery directly.
        .env("COPILOT_SYSTEM_PROXY", "false")
        // The native HTTP stack reads process proxy settings. Loopback requests skip the SDK
        // resolver callback, so inherit the replay fixture's bypasses before OS proxy discovery.
        .env("NO_PROXY", no_proxy)
        .env("no_proxy", no_proxy)
        .env("GIT_TRACE", git_trace_path)
        .env(RESULT_ENV, result_path)
        .env("COPILOT_MINIMAL_HOST_HOME", context.home_dir())
        .env("COPILOT_MINIMAL_HOST_WORKING_DIRECTORY", working_directory)
        .env(
            "COPILOT_MINIMAL_HOST_CONFIG_DIRECTORY",
            context.home_dir().join("host-config"),
        )
        .env(
            "COPILOT_MINIMAL_HOST_COPILOT_API_URL",
            internal_test_options.copilot_api_url.unwrap_or_default(),
        )
        .env(
            "COPILOT_MINIMAL_HOST_GITHUB_API_URL",
            internal_test_options.github_api_url.unwrap_or_default(),
        )
        .env(
            "COPILOT_MINIMAL_HOST_PROXY_URL",
            internal_test_options.proxy_url.unwrap_or_default(),
        )
        .env(
            "COPILOT_MINIMAL_HOST_NO_PROXY",
            internal_test_options.no_proxy.unwrap_or_default(),
        )
        .env(
            "COPILOT_MINIMAL_HOST_CA_PATH",
            internal_test_options
                .ca_certificate_path
                .unwrap_or_default(),
        )
        .current_dir(context.work_dir())
        .kill_on_drop(true);
    // The runtime finds a repository root without spawning `git` only when no inherited Git
    // environment could change what `git` reports (CI's private-source auth exports
    // `GIT_CONFIG_GLOBAL`, for one); with any of these set it spawns `git` instead.
    for name in INHERITED_GIT_ENVIRONMENT {
        command.env_remove(name);
    }
    // Exclude desktop utilities from the strict helper. Keep Git available for the
    // observing rerun so it can identify a denied Git spawn.
    #[cfg(target_os = "linux")]
    if !observe_only {
        command.env("PATH", working_directory);
    }
    if observe_only {
        command.env(OBSERVE_ONLY_ENV, "1");
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.as_std_mut().creation_flags(CREATE_NO_WINDOW);
    }
    command.output().await
}

fn read_git_trace(path: &std::path::Path) -> String {
    std::fs::read_to_string(path).unwrap_or_else(|error| format!("<no git trace: {error}>"))
}

#[test]
#[cfg(any(target_os = "linux", windows))]
fn minimal_host_child_process_helper() {
    if std::env::var_os(HELPER_ENV).is_none() {
        return;
    }

    // KILL_ON_JOB_CLOSE would terminate this process (with a success exit code) if a failed
    // assertion below unwound and dropped the Job, hiding the failure; leak it so it never closes.
    #[cfg(windows)]
    let job: &'static windows_process_count::Job = Box::leak(Box::new(
        windows_process_count::Job::attach_current_process(),
    ));
    #[cfg(target_os = "linux")]
    if std::env::var_os(OBSERVE_ONLY_ENV).is_none() {
        linux_process_filter::deny_child_process_syscalls();
    }

    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("create isolated runtime")
        .block_on(run_minimal_host_scenario());

    #[cfg(windows)]
    assert_eq!(
        job.total_processes(),
        1,
        "only the isolated helper itself should belong to its Job Object"
    );

    std::fs::write(required_path(RESULT_ENV), "completed").expect("write helper completion marker");
}

async fn run_minimal_host_scenario() {
    let home = required_path("COPILOT_MINIMAL_HOST_HOME");
    let working_directory = required_path("COPILOT_MINIMAL_HOST_WORKING_DIRECTORY");
    let config_directory = required_path("COPILOT_MINIMAL_HOST_CONFIG_DIRECTORY");
    let mut in_process = InProcessRuntimeOptions::new()
        .with_owned(|_| OwnedRuntimeOptions::new().with_base_directory(home.clone()));
    in_process.eager_power_shell_resolution = Some(false);
    let options = ClientOptions::new(ClientMode::Empty)
        .with_runtime(RuntimeConnection::InProcess(in_process))
        .with_internal_test_options(InternalTestOptions {
            copilot_api_url: optional_string("COPILOT_MINIMAL_HOST_COPILOT_API_URL"),
            github_api_url: optional_string("COPILOT_MINIMAL_HOST_GITHUB_API_URL"),
            proxy_url: optional_string("COPILOT_MINIMAL_HOST_PROXY_URL"),
            no_proxy: optional_string("COPILOT_MINIMAL_HOST_NO_PROXY"),
            ca_certificate_path: optional_string("COPILOT_MINIMAL_HOST_CA_PATH"),
        })
        // Avoid OS proxy discovery for non-loopback requests; loopback uses the child's NO_PROXY.
        .with_network(
            NetworkConfig::new()
                .with_scope(NetworkScope::Runtime)
                .with_resolve_proxy(|_| async { Ok(Some("DIRECT".to_owned())) }),
        )
        .with_github(|group| {
            group
                .with_token(DEFAULT_TEST_TOKEN)
                .with_use_logged_in_user(false)
        });
    let client = Client::start_with_host_configuration(
        options,
        RuntimeHostConfiguration {
            config_directory: Some(config_directory.to_string_lossy().into_owned()),
            working_directory: Some(working_directory.to_string_lossy().into_owned()),
            ..Default::default()
        },
    )
    .await
    .expect("start minimal in-process runtime");
    let session = client
        .create_session(
            CreateSessionOptions::default()
                .with_working_directory(&working_directory)
                .with_permissions(|group| group.with_on_request(Arc::new(ApproveAllHandler)))
                .with_github(|group| group.with_token(DEFAULT_TEST_TOKEN))
                .with_tools(|group| {
                    group.with_available_tools(
                        ToolSet::new()
                            .add_builtin_many(BUILTIN_TOOLS_ISOLATED.iter().copied())
                            .expect("isolated tools form a valid tool set")
                            .into_vec(),
                    )
                })
                .with_enable_config_discovery(false),
        )
        .await
        .expect("create minimal session");
    session
        .send_and_wait(PROMPT)
        .await
        .expect("complete replayed prompt")
        .expect("replay returns an assistant response");
    session.disconnect().await.expect("disconnect session");
    client.stop().await.expect("stop in-process runtime");
}

fn required_path(name: &str) -> PathBuf {
    PathBuf::from(std::env::var_os(name).unwrap_or_else(|| panic!("missing {name}")))
}

fn optional_string(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.is_empty())
}

#[cfg(windows)]
mod windows_process_count {
    use std::io;
    use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};

    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
        JOBOBJECT_BASIC_ACCOUNTING_INFORMATION, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JobObjectBasicAccountingInformation, JobObjectExtendedLimitInformation,
        QueryInformationJobObject, SetInformationJobObject,
    };
    use windows_sys::Win32::System::Threading::GetCurrentProcess;

    pub(super) struct Job(OwnedHandle);

    impl Job {
        pub(super) fn attach_current_process() -> Self {
            // SAFETY: null attributes and name create a new anonymous Job Object.
            let raw = unsafe { CreateJobObjectW(std::ptr::null(), std::ptr::null()) };
            assert!(
                !raw.is_null(),
                "create Job Object: {}",
                io::Error::last_os_error()
            );
            // SAFETY: the successful call returned a new, uniquely owned handle.
            let job = Self(unsafe { OwnedHandle::from_raw_handle(raw) });

            // SAFETY: this plain Win32 input structure is valid when zero-initialized.
            let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = unsafe { std::mem::zeroed() };
            limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            // SAFETY: the structure and Job Object remain valid for the duration of the call.
            assert_ne!(
                unsafe {
                    SetInformationJobObject(
                        job.0.as_raw_handle(),
                        JobObjectExtendedLimitInformation,
                        std::ptr::addr_of!(limits).cast(),
                        std::mem::size_of_val(&limits) as u32,
                    )
                },
                0,
                "configure Job Object: {}",
                io::Error::last_os_error()
            );
            // SAFETY: GetCurrentProcess returns a valid pseudo-handle, and `job` is owned here.
            assert_ne!(
                unsafe { AssignProcessToJobObject(job.0.as_raw_handle(), GetCurrentProcess()) },
                0,
                "assign helper to Job Object: {}",
                io::Error::last_os_error()
            );
            job
        }

        pub(super) fn total_processes(&self) -> u32 {
            // SAFETY: this plain Win32 output structure is writable and correctly sized.
            let mut accounting: JOBOBJECT_BASIC_ACCOUNTING_INFORMATION =
                unsafe { std::mem::zeroed() };
            // SAFETY: `self` owns a valid Job Object and the output buffer remains writable.
            assert_ne!(
                unsafe {
                    QueryInformationJobObject(
                        self.0.as_raw_handle(),
                        JobObjectBasicAccountingInformation,
                        std::ptr::addr_of_mut!(accounting).cast(),
                        std::mem::size_of_val(&accounting) as u32,
                        std::ptr::null_mut(),
                    )
                },
                0,
                "query Job Object accounting: {}",
                io::Error::last_os_error()
            );
            accounting.TotalProcesses
        }
    }
}

#[cfg(target_os = "linux")]
mod linux_process_filter {
    fn statement(code: u16, value: u32) -> libc::sock_filter {
        libc::sock_filter {
            code,
            jt: 0,
            jf: 0,
            k: value,
        }
    }

    fn jump(code: u16, value: u32, yes: u8, no: u8) -> libc::sock_filter {
        libc::sock_filter {
            code,
            jt: yes,
            jf: no,
            k: value,
        }
    }

    extern "C" fn report_denied_syscall(
        _signal: libc::c_int,
        _info: *mut libc::siginfo_t,
        _context: *mut libc::c_void,
    ) {
        // Not async-signal-safe, but the process exits right after, and the backtrace names the
        // runtime code that tried to create a child process.
        eprintln!(
            "child-process syscall denied; backtrace:\n{}",
            std::backtrace::Backtrace::force_capture()
        );
        // SAFETY: `_exit` terminates the process without running further Rust code.
        unsafe { libc::_exit(86) };
    }

    pub(super) fn deny_child_process_syscalls() {
        const BPF_LD_W_ABS: u16 = 0x20;
        const BPF_JMP_JEQ_K: u16 = 0x15;
        const BPF_JMP_JSET_K: u16 = 0x45;
        const BPF_RET_K: u16 = 0x06;
        const SECCOMP_RET_TRAP: u32 = 0x0003_0000;
        const SECCOMP_RET_ERRNO: u32 = 0x0005_0000;
        const SECCOMP_RET_ALLOW: u32 = 0x7fff_0000;
        const CLONE_THREAD: u32 = 0x0001_0000;

        // libc exports the legacy fork and vfork syscalls only where the kernel has them
        // (not aarch64). Elsewhere those libc calls go through `clone`, which is denied below.
        #[cfg(target_arch = "x86_64")]
        let denied = [
            libc::SYS_execve,
            libc::SYS_execveat,
            libc::SYS_fork,
            libc::SYS_vfork,
        ];
        #[cfg(not(target_arch = "x86_64"))]
        let denied = [libc::SYS_execve, libc::SYS_execveat];

        let mut filter = vec![
            statement(BPF_LD_W_ABS, 0),
            jump(BPF_JMP_JEQ_K, libc::SYS_clone as u32, 0, 3),
            statement(BPF_LD_W_ABS, 16),
            jump(BPF_JMP_JSET_K, CLONE_THREAD, denied.len() as u8 + 5, 0),
            statement(BPF_RET_K, SECCOMP_RET_TRAP),
            jump(BPF_JMP_JEQ_K, libc::SYS_clone3 as u32, 0, 1),
            statement(BPF_RET_K, SECCOMP_RET_ERRNO | libc::ENOSYS as u32),
        ];
        // Each match jumps to the TRAP return that follows the ALLOW return.
        for (index, syscall) in denied.iter().enumerate() {
            filter.push(jump(
                BPF_JMP_JEQ_K,
                *syscall as u32,
                (denied.len() - index) as u8,
                0,
            ));
        }
        filter.extend([
            statement(BPF_RET_K, SECCOMP_RET_ALLOW),
            statement(BPF_RET_K, SECCOMP_RET_TRAP),
            statement(BPF_RET_K, SECCOMP_RET_ALLOW),
        ]);
        let mut program = libc::sock_fprog {
            len: filter.len() as u16,
            filter: filter.as_mut_ptr(),
        };

        // SAFETY: this plain C input structure is valid when zero-initialized.
        let mut action: libc::sigaction = unsafe { std::mem::zeroed() };
        action.sa_sigaction = report_denied_syscall as *const () as libc::sighandler_t;
        action.sa_flags = libc::SA_SIGINFO;
        // SAFETY: `action` is fully initialized and the handler has the SA_SIGINFO signature.
        assert_eq!(
            unsafe { libc::sigaction(libc::SIGSYS, &action, std::ptr::null_mut()) },
            0,
            "install SIGSYS handler: {}",
            std::io::Error::last_os_error()
        );
        // `clone3` returns ENOSYS so pthread creation can fall back to `clone`, whose
        // CLONE_THREAD flag distinguishes worker threads from child processes.
        // SAFETY: these `prctl` calls install a valid, process-local BPF filter.
        assert_eq!(
            unsafe { libc::prctl(libc::PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) },
            0,
            "set no_new_privs: {}",
            std::io::Error::last_os_error()
        );
        // SAFETY: `program` and its filter remain alive for the duration of the call.
        assert_eq!(
            unsafe {
                libc::prctl(
                    libc::PR_SET_SECCOMP,
                    libc::SECCOMP_MODE_FILTER,
                    &mut program,
                )
            },
            0,
            "install child-process seccomp filter: {}",
            std::io::Error::last_os_error()
        );
    }
}
