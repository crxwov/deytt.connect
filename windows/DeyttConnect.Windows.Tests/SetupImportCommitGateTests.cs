using DeyttConnect.Windows.Views;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class SetupImportCommitGateTests
{
    [Fact]
    public async Task Concurrent_completion_is_ignored_and_failed_commit_can_be_retried()
    {
        var gate = new SetupImportCommitGate();
        var commitStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var finishCommit = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var commitCount = 0;

        async Task CommitAsync()
        {
            if (!gate.TryEnter())
                return;
            try
            {
                commitCount++;
                commitStarted.TrySetResult();
                await finishCommit.Task;
            }
            finally
            {
                gate.Exit();
            }
        }

        var firstCommit = CommitAsync();
        await commitStarted.Task;
        await CommitAsync();
        Assert.Equal(1, commitCount);

        finishCommit.SetException(new IOException("commit failed"));
        await Assert.ThrowsAsync<IOException>(() => firstCommit);

        Assert.True(gate.TryEnter());
        gate.Exit();
        Assert.Equal(1, commitCount);
    }
}
