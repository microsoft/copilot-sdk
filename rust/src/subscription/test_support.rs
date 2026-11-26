// Copyright (c) Microsoft Corporation. All rights reserved.

#![cfg(any(test, feature = "test-support"))]

use super::*;

impl ResumeBootstrap {
    pub(crate) fn unclaimed_len(&self) -> Option<usize> {
        match &*self.state.lock() {
            ResumeBootstrapState::Unclaimed(events) => Some(events.len()),
            ResumeBootstrapState::Claimed(_) | ResumeBootstrapState::Disabled => None,
        }
    }
}
