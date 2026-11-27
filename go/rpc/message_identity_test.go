package rpc

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestUserMessageDataPreservesResponseSchemaPropertyOrder(t *testing.T) {
	const schema = `{"type":"json_schema","jsonSchema":{"schema":{"properties":{"b":{},"a":{}}}}}`
	var message UserMessageData
	if err := json.Unmarshal([]byte(`{"content":"hello","responsesTextFormat":{"initialFormat":`+schema+`,"format":`+schema+`}}`), &message); err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(message)
	if err != nil {
		t.Fatal(err)
	}
	for _, field := range []string{"initialFormat", "format"} {
		if !strings.Contains(string(encoded), `"`+field+`":`+schema) {
			t.Errorf("%s lost schema property order: %s", field, encoded)
		}
	}
}
