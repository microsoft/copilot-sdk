// Copyright (c) Microsoft Corporation. All rights reserved.

#![cfg(any(test, feature = "test-support"))]

use super::*;

impl Session {
    /// Count resume events retained for a subscriber that has not claimed them,
    /// or `None` once nothing is retained. This is test-harness plumbing.
    #[doc(hidden)]
    pub fn unclaimed_resume_backlog_len_for_test(&self) -> Option<usize> {
        self.resume_bootstrap.as_ref()?.unclaimed_len()
    }
}
