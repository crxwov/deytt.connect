using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class WindowsPaymentFlowStateTests
{
    [Fact]
    public async Task QuoteFailureCanBeRetriedThroughInjectedDelegates()
    {
        var state = new WindowsPaymentFlowState();
        var quoteCalls = 0;
        var checkoutCalls = 0;
        var result = new TelegramCheckout(string.Empty, "test-id");

        await Assert.ThrowsAsync<IOException>(() => state.StartAsync(
            () => ++quoteCalls == 1
                ? Task.FromException<TelegramQuote>(new IOException("quote unavailable"))
                : Task.FromResult(Quote()),
            _ => Task.FromResult<string?>("stars"),
            _ => { checkoutCalls++; return Task.FromResult(result); }));

        Assert.True(state.CanRetry);
        var retried = await state.RetryAsync();

        Assert.Same(result, retried);
        Assert.Equal(2, quoteCalls);
        Assert.Equal(1, checkoutCalls);
        Assert.Equal("test-id", state.PendingPaymentId);
        Assert.False(state.CanRetry);
    }

    [Fact]
    public async Task CheckoutFailureRetriesSameQuotedMethodAndClearsOldPendingId()
    {
        var state = new WindowsPaymentFlowState();
        await state.StartAsync(() => Task.FromResult(Quote()),
            _ => Task.FromResult<string?>("stars"),
            _ => Task.FromResult(new TelegramCheckout(string.Empty, "test-id")));
        var quoteCalls = 0;
        var methodCalls = 0;
        var checkoutMethods = new List<string>();
        var failedOnce = false;
        var recovered = new TelegramCheckout(string.Empty, "next-id");

        await Assert.ThrowsAsync<IOException>(() => state.StartAsync(
            () => { quoteCalls++; return Task.FromResult(Quote()); },
            _ => { methodCalls++; return Task.FromResult<string?>("platega"); },
            method =>
            {
                checkoutMethods.Add(method);
                if (!failedOnce)
                {
                    failedOnce = true;
                    return Task.FromException<TelegramCheckout>(new IOException("checkout unavailable"));
                }
                return Task.FromResult(recovered);
            }));

        Assert.Null(state.PendingPaymentId);
        Assert.True(state.CanRetry);
        Assert.True(state.RetryUsesConfirmedQuote);
        var retried = await state.RetryAsync();

        Assert.Same(recovered, retried);
        Assert.Equal(1, quoteCalls);
        Assert.Equal(1, methodCalls);
        Assert.Equal(["platega", "platega"], checkoutMethods);
        Assert.Equal("next-id", state.PendingPaymentId);
        Assert.False(state.RetryUsesConfirmedQuote);
    }

    [Fact]
    public async Task BillingFlowGuardPreventsOverlappingQuoteAndDuplicateCheckout()
    {
        var state = new WindowsPaymentFlowState();
        var quoteRequested = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var releaseQuote = new TaskCompletionSource<TelegramQuote>(TaskCreationOptions.RunContinuationsAsynchronously);
        var checkoutCalls = 0;

        async Task<bool> StartFlowAsync()
        {
            if (!state.TryEnterBillingFlow())
                return false;
            try
            {
                await state.StartAsync(
                    () => { quoteRequested.TrySetResult(); return releaseQuote.Task; },
                    _ => Task.FromResult<string?>("stars"),
                    _ => { checkoutCalls++; return Task.FromResult(new TelegramCheckout(string.Empty, "test-id")); });
                return true;
            }
            finally
            {
                state.ExitBillingFlow();
            }
        }

        var firstFlow = StartFlowAsync();
        await quoteRequested.Task;
        var overlappingFlowStarted = await StartFlowAsync();
        releaseQuote.SetResult(Quote());
        var firstFlowStarted = await firstFlow;

        Assert.True(firstFlowStarted);
        Assert.False(overlappingFlowStarted);
        Assert.Equal(1, checkoutCalls);
        Assert.True(state.TryEnterBillingFlow());
        state.ExitBillingFlow();
    }

    [Fact]
    public async Task CancelingMethodSelectionDoesNotCreateCheckout()
    {
        var state = new WindowsPaymentFlowState();
        var checkoutCalls = 0;
        var result = await state.StartAsync(
            () => Task.FromResult(Quote()),
            _ => Task.FromResult<string?>(null),
            _ => { checkoutCalls++; return Task.FromResult(new TelegramCheckout(string.Empty, "test-id")); });

        Assert.Null(result);
        Assert.Equal(0, checkoutCalls);
        Assert.False(state.CanRetry);
        Assert.Null(state.PendingPaymentId);
    }

    [Fact]
    public async Task CheckoutWithoutExternalIdHasNoActionablePendingPayment()
    {
        var state = new WindowsPaymentFlowState();
        var checkout = await state.StartAsync(
            () => Task.FromResult(Quote()),
            _ => Task.FromResult<string?>("stars"),
            _ => Task.FromResult(new TelegramCheckout(string.Empty, null)));

        Assert.NotNull(checkout);
        Assert.Null(state.PendingPaymentId);
        Assert.False(state.CanRetry);
    }

    [Fact]
    public async Task PendingAndStatusErrorKeepPaymentActionable()
    {
        var state = await StateWithPendingPayment();

        Assert.False(state.ApplyPaymentStatus("pending", "test-id"));
        Assert.Equal("test-id", state.PendingPaymentId);
        Assert.False(state.ApplyPaymentStatus(null, "test-id"));
        Assert.Equal("test-id", state.PendingPaymentId);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task PaidClearsPendingAndReportsRefreshResult(bool refreshSucceeded)
    {
        var state = await StateWithPendingPayment();

        var refreshed = state.ApplyPaymentStatus("paid", "test-id", refreshSucceeded);

        Assert.Equal(refreshSucceeded, refreshed);
        Assert.Null(state.PendingPaymentId);
    }

    [Fact]
    public async Task DelayedPaidResponseCannotClearNewerPendingPayment()
    {
        var state = await StateWithPendingPayment();
        await state.StartAsync(() => Task.FromResult(Quote()),
            _ => Task.FromResult<string?>("platega"),
            _ => Task.FromResult(new TelegramCheckout(string.Empty, "newer-id")));

        var refreshed = state.ApplyPaymentStatus("paid", "test-id", true);

        Assert.True(refreshed);
        Assert.Equal("newer-id", state.PendingPaymentId);
    }

    private static async Task<WindowsPaymentFlowState> StateWithPendingPayment()
    {
        var state = new WindowsPaymentFlowState();
        await state.StartAsync(() => Task.FromResult(Quote()),
            _ => Task.FromResult<string?>("stars"),
            _ => Task.FromResult(new TelegramCheckout(string.Empty, "test-id")));
        return state;
    }

    private static TelegramQuote Quote() => new("Plan", 2, 3, 100, 50);
}
