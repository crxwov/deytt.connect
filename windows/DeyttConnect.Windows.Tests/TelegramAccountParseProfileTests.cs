using System.Net;
using System.Text.Json;
using DeyttConnect.Windows.Services;
using Xunit;

namespace DeyttConnect.Windows.Tests;

public sealed class TelegramAccountParseProfileTests
{
    [Fact]
    public void ParseProfile_PreservesNullableInt32AndInt64Values()
    {
        using var document = JsonDocument.Parse("""
            {
              "service_days": 9223372036854775807,
              "subscription": {
                "device_limit": 2147483647,
                "devices_used": -2147483648,
                "traffic_limit_bytes": 9223372036854775807,
                "traffic_used_bytes": -9223372036854775808,
                "traffic_total_bytes": 9007199254740993
              }
            }
            """);

        var account = TelegramAccount.ParseProfile(document.RootElement);

        Assert.Equal(long.MaxValue, account.ServiceDays);
        Assert.NotNull(account.Subscription);
        Assert.Equal(int.MaxValue, account.Subscription.DeviceLimit);
        Assert.Equal(int.MinValue, account.Subscription.DevicesUsed);
        Assert.Equal(long.MaxValue, account.Subscription.TrafficLimitBytes);
        Assert.Equal(long.MinValue, account.Subscription.TrafficUsedBytes);
        Assert.Equal(9007199254740993L, account.Subscription.TrafficTotalBytes);
    }

    [Fact]
    public void ParseProfile_LeavesAbsentAndNullNullableNumbersUnset()
    {
        using var document = JsonDocument.Parse("""
            {
              "service_days": null,
              "subscription": {
                "device_limit": null,
                "devices_used": 0,
                "traffic_limit_bytes": null,
                "traffic_used_bytes": 0
              }
            }
            """);

        var account = TelegramAccount.ParseProfile(document.RootElement);

        Assert.Null(account.ServiceDays);
        Assert.NotNull(account.Subscription);
        Assert.Null(account.Subscription.DeviceLimit);
        Assert.Equal(0, account.Subscription.DevicesUsed);
        Assert.Null(account.Subscription.TrafficLimitBytes);
        Assert.Equal(0L, account.Subscription.TrafficUsedBytes);
        Assert.Null(account.Subscription.TrafficTotalBytes);
    }

    [Theory]
    [InlineData("\"12\"")]
    [InlineData("1.5")]
    [InlineData("2147483648")]
    [InlineData("-2147483649")]
    public void ParseProfile_TreatsMalformedInt32ValuesAsUnset(string jsonNumber)
    {
        using var document = JsonDocument.Parse($"{{\"subscription\":{{\"device_limit\":{jsonNumber}}}}}");

        var account = TelegramAccount.ParseProfile(document.RootElement);

        Assert.Null(account.Subscription?.DeviceLimit);
    }

    [Theory]
    [InlineData("\"12\"")]
    [InlineData("1.5")]
    [InlineData("9223372036854775808")]
    [InlineData("-9223372036854775809")]
    public void ParseProfile_TreatsMalformedInt64ValuesAsUnset(string jsonNumber)
    {
        using var document = JsonDocument.Parse($"{{\"service_days\":{jsonNumber}}}");

        var account = TelegramAccount.ParseProfile(document.RootElement);

        Assert.Null(account.ServiceDays);
    }

    [Fact]
    public void ParseProfile_AllProfileFieldsAreOptionalAndWrongTypesUseCurrentDefaults()
    {
        using var document = JsonDocument.Parse("""
            {
              "username": 17,
              "first_name": false,
              "blocked": "true",
              "last_name": [],
              "registered_at": 2026,
              "subscription": "invalid"
            }
            """);

        var account = TelegramAccount.ParseProfile(document.RootElement);

        Assert.Equal("", account.Username);
        Assert.Equal("", account.FirstName);
        Assert.False(account.Blocked);
        Assert.Equal("", account.LastName);
        Assert.Null(account.RegisteredAt);
        Assert.Null(account.Subscription);
    }

    [Theory]
    [InlineData("null")]
    [InlineData("[]")]
    [InlineData("42")]
    [InlineData("\"profile\"")]
    public void ParseProfile_RequiresAnObject(string json)
    {
        using var document = JsonDocument.Parse(json);

        var exception = Assert.Throws<TelegramApiException>(() => TelegramAccount.ParseProfile(document.RootElement));

        Assert.Equal("invalid_response", exception.Code);
        Assert.Equal(HttpStatusCode.BadGateway, exception.StatusCode);
    }
}
