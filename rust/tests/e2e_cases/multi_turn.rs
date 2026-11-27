use copilot_sdk::SessionEvent;
use copilot_sdk::session_events::{
    SessionEventType, ToolExecutionCompleteData, ToolExecutionStartData,
};

use super::support::{assistant_message_content, collect_until_idle, event_types};

#[tokio::test]
async fn should_handle_file_creation_then_reading_across_turns() {
    super::support::with_shared_e2e_context(
        &E2E,
        "multi_turn",
        "should_handle_file_creation_then_reading_across_turns",
        |ctx| {
            Box::pin(async move {
                ctx.set_default_copilot_user();
                let client = ctx.start_client().await;
                let session = client
                    .create_session(ctx.approve_all_session_config())
                    .await
                    .expect("create session");

                let create_events = session.subscribe();
                session
                    .send_and_wait(
                        "Create a file called 'greeting.txt' with the content \
                         'Hello from multi-turn test'.",
                    )
                    .await
                    .expect("create file turn");
                assert_eq!(
                    std::fs::read_to_string(ctx.work_dir().join("greeting.txt"))
                        .expect("read greeting"),
                    "Hello from multi-turn test"
                );
                assert_tool_turn_ordering(
                    &collect_until_idle(create_events).await,
                    "file creation turn",
                );

                let read_events = session.subscribe();
                let answer = session
                    .send_and_wait("Read the file 'greeting.txt' and tell me its exact contents.")
                    .await
                    .expect("read file turn")
                    .expect("assistant message");
                assert!(assistant_message_content(&answer).contains("Hello from multi-turn test"));
                assert_tool_turn_ordering(&collect_until_idle(read_events).await, "file read turn");

                session.disconnect().await.expect("disconnect session");
                client.stop().await.expect("stop client");
            })
        },
    )
    .await;
}

fn assert_tool_turn_ordering(events: &[SessionEvent], turn_description: &str) {
    let observed_types = event_types(events).join(", ");
    let user_message = index_of(events, SessionEventType::UserMessage, 0);
    let tool_starts: Vec<_> = events
        .iter()
        .enumerate()
        .filter(|(_, event)| event.parsed_type() == SessionEventType::ToolExecutionStart)
        .collect();
    let tool_completes: Vec<_> = events
        .iter()
        .enumerate()
        .filter(|(_, event)| event.parsed_type() == SessionEventType::ToolExecutionComplete)
        .collect();

    assert!(
        user_message.is_some(),
        "expected user.message in {turn_description}; observed: {observed_types}"
    );
    assert!(
        !tool_starts.is_empty(),
        "expected tool.execution_start in {turn_description}; observed: {observed_types}"
    );
    assert!(
        !tool_completes.is_empty(),
        "expected tool.execution_complete in {turn_description}; observed: {observed_types}"
    );
    assert!(user_message.unwrap() < tool_starts[0].0);

    let starts: Vec<(usize, ToolExecutionStartData)> = tool_starts
        .iter()
        .map(|(index, event)| {
            let data = event
                .typed_data::<ToolExecutionStartData>()
                .expect("typed tool.execution_start");
            assert!(!data.tool_call_id.is_empty());
            assert!(!data.tool_name.is_empty());
            (*index, data)
        })
        .collect();
    for (complete_index, event) in &tool_completes {
        let complete = event
            .typed_data::<ToolExecutionCompleteData>()
            .expect("typed tool.execution_complete");
        assert!(!complete.tool_call_id.is_empty());
        assert!(
            starts.iter().any(|(start_index, start)| {
                start.tool_call_id == complete.tool_call_id && start_index < complete_index
            }),
            "tool.execution_complete {} has no preceding start in {turn_description}",
            complete.tool_call_id
        );
    }

    let last_tool_complete = tool_completes
        .last()
        .map(|(index, _)| *index)
        .expect("last tool completion");
    let assistant = index_of(
        events,
        SessionEventType::AssistantMessage,
        last_tool_complete + 1,
    )
    .expect("assistant.message after tools");
    let idle = index_of(events, SessionEventType::SessionIdle, assistant + 1)
        .expect("session.idle after assistant");
    assert!(last_tool_complete < assistant);
    assert!(assistant < idle);
}

fn index_of(
    events: &[SessionEvent],
    event_type: SessionEventType,
    start_index: usize,
) -> Option<usize> {
    events
        .iter()
        .enumerate()
        .skip(start_index)
        .find_map(|(index, event)| (event.parsed_type() == event_type).then_some(index))
}
static E2E: super::support::SharedE2eGroup =
    super::support::SharedE2eGroup::standard("multi_turn", 1);
