// Copyright (c) Microsoft Corporation. All rights reserved.

//! Minimal source bridge loaded by `build.rs` so build-time provisioning shares
//! `cache_paths` and `platform_dirs` with the library crate without `#[path]`.

#[allow(dead_code)]
pub mod cache_paths;
#[allow(dead_code)]
pub mod platform_dirs;
