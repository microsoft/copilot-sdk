use copilot_sdk::session_events::{
    AssistantMessageData, AssistantMessageDeltaData, AssistantMessageStartData, SessionEventType,
};

use super::support::collect_until_idle;

#[tokio::test]
async fn should_not_produce_deltas_when_streaming_is_disabled() {
    super::support::with_shared_e2e_context(&E2E,
        "streaming_fidelity",
        "should_not_produce_deltas_when_streaming_is_disabled",
        |ctx| {
            Box::pin(async move {
                ctx.set_default_copilot_user();
                let client = ctx.start_client().await;
                let session = client
                    .create_session(ctx.approve_all_session_config().with_events(|group| group.with_streaming(false)))
                    .await
                    .expect("create session");
                let events = session.subscribe();

                session
                    .send_and_wait("Say 'hello world'.")
                    .await
                    .expect("send");

                let observed = collect_until_idle(events).await;
                assert!(
                    observed
                        .iter()
                        .all(|event| event.parsed_type() != SessionEventType::AssistantMessageDelta),
                    "streaming-disabled sessions should not emit assistant.message_delta"
                );
                assert!(
                    observed
                        .iter()
                        .any(|event| event.parsed_type() == SessionEventType::AssistantMessage),
                    "expected final assistant.message"
                );

                session.disconnect().await.expect("disconnect session");
                client.stop().await.expect("stop client");
            })
        },
    )
    .await;
}

#[tokio::test]
async fn should_emit_assistantmessage_start_before_deltas_with_matching_messageid() {
    super::support::with_shared_e2e_context(
        &E2E,
        "streaming_fidelity",
        "should_emit_assistantmessagestart_before_deltas_with_matching_messageid",
        |ctx| {
            Box::pin(async move {
                ctx.set_default_copilot_user();
                let client = ctx.start_client().await;
                let session = client
                    .create_session(
                        ctx.approve_all_session_config()
                            .with_events(|group| group.with_streaming(true)),
                    )
                    .await
                    .expect("create session");
                let events = session.subscribe();

                session
                    .send_and_wait("Count from 1 to 5, separated by commas.")
                    .await
                    .expect("send");

                let observed = collect_until_idle(events).await;
                let start_indices: Vec<_> = observed
                    .iter()
                    .enumerate()
                    .filter_map(|(index, event)| {
                        (event.parsed_type() == SessionEventType::AssistantMessageStart)
                            .then_some(index)
                    })
                    .collect();
                let delta_indices: Vec<_> = observed
                    .iter()
                    .enumerate()
                    .filter_map(|(index, event)| {
                        (event.parsed_type() == SessionEventType::AssistantMessageDelta)
                            .then_some(index)
                    })
                    .collect();
                assert!(
                    !start_indices.is_empty(),
                    "expected assistant.message_start"
                );
                assert!(
                    !delta_indices.is_empty(),
                    "expected assistant.message_delta"
                );
                assert!(start_indices[0] < delta_indices[0]);
                assert_has_content_deltas(&observed);
                let final_message = observed
                    .iter()
                    .rposition(|event| event.parsed_type() == SessionEventType::AssistantMessage)
                    .expect("assistant message index");
                assert!(delta_indices[0] < final_message);

                let message_ids: Vec<_> = observed
                    .iter()
                    .filter_map(|event| event.typed_data::<AssistantMessageData>())
                    .map(|data| data.message_id)
                    .collect();
                for start_index in start_indices {
                    let data = observed[start_index]
                        .typed_data::<AssistantMessageStartData>()
                        .expect("assistant.message_start data");
                    assert!(!data.message_id.is_empty());
                    assert!(message_ids.contains(&data.message_id));
                }

                session.disconnect().await.expect("disconnect session");
                client.stop().await.expect("stop client");
            })
        },
    )
    .await;
}

fn assert_has_content_deltas(events: &[copilot_sdk::SessionEvent]) {
    let deltas: Vec<_> = events
        .iter()
        .filter(|event| event.parsed_type() == SessionEventType::AssistantMessageDelta)
        .collect();
    assert!(
        !deltas.is_empty(),
        "expected assistant.message_delta events"
    );
    for delta in deltas {
        let data = delta
            .typed_data::<AssistantMessageDeltaData>()
            .expect("assistant.message_delta data");
        assert!(!data.delta_content.is_empty());
    }
}
static E2E: super::support::SharedE2eGroup =
    super::support::SharedE2eGroup::standard("streaming_fidelity", 2);
