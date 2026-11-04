namespace DeyttConnect.Windows.Service;

internal static class ConnectDnsPrerequisiteResolver
{
    internal static async Task<(TFirst? First, TSecond? Second)> ResolveAsync<TFirst, TSecond>(
        Func<CancellationToken, Task<TFirst>> first,
        Func<CancellationToken, Task<TSecond>> second,
        Func<TFirst, bool> firstSucceeded,
        Func<TSecond, bool> secondSucceeded,
        CancellationToken cancellationToken)
        where TFirst : class
        where TSecond : class
    {
        using var linkedCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var firstTask = first(linkedCancellation.Token);
        var secondTask = second(linkedCancellation.Token);

        try
        {
            if (await Task.WhenAny(firstTask, secondTask).ConfigureAwait(false) == firstTask)
            {
                var firstResult = await firstTask.ConfigureAwait(false);
                if (!firstSucceeded(firstResult))
                {
                    linkedCancellation.Cancel();
                    await ObserveAsync(secondTask).ConfigureAwait(false);
                    return (firstResult, null);
                }

                var secondResult = await secondTask.ConfigureAwait(false);
                return (firstResult, secondResult);
            }

            var completedSecond = await secondTask.ConfigureAwait(false);
            if (!secondSucceeded(completedSecond))
            {
                linkedCancellation.Cancel();
                await ObserveAsync(firstTask).ConfigureAwait(false);
                return (null, completedSecond);
            }

            var completedFirst = await firstTask.ConfigureAwait(false);
            return (completedFirst, completedSecond);
        }
        catch
        {
            linkedCancellation.Cancel();
            await ObserveAsync(Task.WhenAll(firstTask, secondTask)).ConfigureAwait(false);
            throw;
        }
    }

    private static async Task ObserveAsync(Task task)
    {
        try { await task.ConfigureAwait(false); }
        catch { }
    }
}
