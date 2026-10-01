namespace DeyttConnect.Windows.Services;

internal sealed class WindowsPaymentFlowState
{
    private Func<Task<TelegramCheckout?>>? _retry;
    private int _billingFlowInProgress;

    public string? PendingPaymentId { get; private set; }
    public bool CanRetry => _retry is not null;
    public bool RetryUsesConfirmedQuote { get; private set; }

    public bool TryEnterBillingFlow() =>
        Interlocked.CompareExchange(ref _billingFlowInProgress, 1, 0) == 0;

    public void ExitBillingFlow() => Volatile.Write(ref _billingFlowInProgress, 0);

    public async Task<TelegramCheckout?> StartAsync(
        Func<Task<TelegramQuote>> getQuote,
        Func<TelegramQuote, Task<string?>> chooseMethod,
        Func<string, Task<TelegramCheckout>> createCheckout)
    {
        ArgumentNullException.ThrowIfNull(getQuote);
        ArgumentNullException.ThrowIfNull(chooseMethod);
        ArgumentNullException.ThrowIfNull(createCheckout);

        RetryUsesConfirmedQuote = false;
        _retry = () => StartAsync(getQuote, chooseMethod, createCheckout);
        var quote = await getQuote();
        var method = await chooseMethod(quote);
        if (method is null)
        {
            _retry = null;
            RetryUsesConfirmedQuote = false;
            return null;
        }

        // A newly selected checkout replaces the previous actionable payment only
        // once the user has confirmed a payment method.
        PendingPaymentId = null;
        return await CreateCheckoutAsync(quote, method, createCheckout);
    }

    public Task<TelegramCheckout?> RetryAsync() =>
        _retry is { } retry ? retry() : Task.FromResult<TelegramCheckout?>(null);

    public void ApplyCheckout(TelegramCheckout checkout)
    {
        ArgumentNullException.ThrowIfNull(checkout);
        PendingPaymentId = string.IsNullOrWhiteSpace(checkout.ExternalId) ? null : checkout.ExternalId;
        _retry = null;
        RetryUsesConfirmedQuote = false;
    }

    public bool ApplyPaymentStatus(
        string? status,
        string? queriedExternalId,
        bool accountRefreshSucceeded = false)
    {
        if (!string.Equals(status, "paid", StringComparison.OrdinalIgnoreCase))
            return false;

        if (!string.IsNullOrWhiteSpace(queriedExternalId) &&
            string.Equals(PendingPaymentId, queriedExternalId, StringComparison.Ordinal))
            PendingPaymentId = null;
        return accountRefreshSucceeded;
    }

    public void Reset()
    {
        PendingPaymentId = null;
        _retry = null;
        RetryUsesConfirmedQuote = false;
    }

    private async Task<TelegramCheckout?> CreateCheckoutAsync(
        TelegramQuote quote,
        string method,
        Func<string, Task<TelegramCheckout>> createCheckout)
    {
        RetryUsesConfirmedQuote = true;
        _retry = () => CreateCheckoutAsync(quote, method, createCheckout);
        var checkout = await createCheckout(method);
        ApplyCheckout(checkout);
        return checkout;
    }
}
