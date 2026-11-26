// Copyright (c) Microsoft Corporation. All rights reserved.

#![cfg(any(test, feature = "test-support"))]

use super::*;

impl SessionRouter {
    pub(crate) fn session_count(&self) -> usize {
        self.state.lock().sessions.len()
    }
}
