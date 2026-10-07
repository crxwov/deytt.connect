using System.Text.Json.Nodes;

namespace DeyttConnect.Windows.Services;

internal static class WindowsSubscriptionProfileNormalizer
{
    internal static string AddTunForEmptyInbounds(string profileJson)
    {
        var root = JsonNode.Parse(profileJson) as JsonObject;
        if (root?["inbounds"] is not JsonArray { Count: 0 } inbounds)
            return profileJson;

        inbounds.Add(new JsonObject
        {
            ["type"] = "tun",
            ["tag"] = "tun-in",
            ["address"] = new JsonArray(JsonValue.Create("172.19.0.1/30")),
            ["auto_route"] = true,
            ["strict_route"] = true,
        });
        return root.ToJsonString();
    }
}
