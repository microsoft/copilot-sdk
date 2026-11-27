// Copyright (c) Microsoft Corporation. All rights reserved.

package rpc_test

import (
	"encoding/json"
	"fmt"

	"github.com/microsoft/copilot-sdk/go/v2/rpc"
)

func ExampleRepoSettingsReadRawResult() {
	const input = `{"config":{"custom":{"items":[true,null,12.5]}},"invalid":false}`
	var result rpc.RepoSettingsReadRawResult
	if err := json.Unmarshal([]byte(input), &result); err != nil {
		panic(err)
	}
	output, err := json.Marshal(result)
	if err != nil {
		panic(err)
	}
	fmt.Println(string(output))
	// Output: {"config":{"custom":{"items":[true,null,12.5]}},"invalid":false}
}
