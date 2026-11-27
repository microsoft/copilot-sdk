from copilot.session_events import UserMessageData


def test_response_text_format_does_not_shift_user_message_positional_arguments():
    message = UserMessageData("hello", *([None] * 13), "terminal")

    assert message.source == "terminal"
    assert message.responses_text_format is None
    assert UserMessageData.__match_args__[14] == "source"
