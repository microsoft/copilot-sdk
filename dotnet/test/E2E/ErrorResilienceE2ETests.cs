/*---------------------------------------------------------------------------------------------
 *  Copyright (c) Microsoft Corporation. All rights reserved.
 *--------------------------------------------------------------------------------------------*/

using Xunit;
using Xunit.Abstractions;

namespace Microsoft.Copilot.Test.E2E;

/// <summary>
/// Verifies sending to a disposed session fails. Mirrors
/// <c>nodejs/test/e2e/error_resilience.e2e.test.ts</c>.
/// </summary>
public class ErrorResilienceE2ETests(E2ETestFixture fixture, ITestOutputHelper output)
    : E2ETestBase(fixture, "error_resilience", output)
{
    [Fact]
    public async Task Should_Throw_When_Sending_To_Disconnected_Session()
    {
        var session = await CreateSessionAsync();
        await session.DisposeAsync();

        await Assert.ThrowsAnyAsync<Exception>(() =>
            session.SendAndWaitAsync(new MessageOptions { Prompt = "Hello" }));
    }
}
