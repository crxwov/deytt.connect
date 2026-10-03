using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Media;

namespace DeyttConnect.Windows.UI;

internal static class RouteFlagVisual
{
    public static Border Create(string countryCode, double width = 42, double height = 28)
    {
        var code = countryCode.ToUpperInvariant();
        var content = code switch
        {
            "NL" => HorizontalStripes(width, height, "#C93D4B", "#F7F7F4", "#31579A"),
            "DE" => HorizontalStripes(width, height, "#17191E", "#C83C4A", "#F0C66B"),
            "RU" => HorizontalStripes(width, height, "#F7F7F4", "#477CC0", "#C83C4A"),
            "FI" => FinnishFlag(width, height),
            "RU-DE" => DoubleFlag(width, height),
            _ => Fallback(code, width, height),
        };

        var frame = Frame(content, width, height);
        AutomationProperties.SetName(frame, FlagName(code));
        return frame;
    }

    private static Control HorizontalStripes(double width, double height, params string[] colors)
    {
        var rows = new Grid
        {
            Width = width - 2,
            Height = height - 2,
            RowDefinitions = new RowDefinitions("*,*,*"),
        };
        for (var index = 0; index < colors.Length; index++)
        {
            var stripe = new Border { Background = new SolidColorBrush(Color.Parse(colors[index])) };
            Grid.SetRow(stripe, index);
            rows.Children.Add(stripe);
        }
        return rows;
    }

    private static Control FinnishFlag(double width, double height)
    {
        var field = new Grid
        {
            Width = width - 2,
            Height = height - 2,
        };
        field.Children.Add(new Border { Background = new SolidColorBrush(Color.Parse("#F7F7F4")) });
        var blue = new SolidColorBrush(Color.Parse("#3973B4"));
        field.Children.Add(new Border
        {
            Height = Math.Max(4, height * 0.2),
            Background = blue,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        });
        field.Children.Add(new Border
        {
            Width = Math.Max(4, width * 0.14),
            Margin = new Avalonia.Thickness(width * 0.29, 0, 0, 0),
            Background = blue,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left,
        });
        return field;
    }

    private static Control DoubleFlag(double width, double height)
    {
        var pair = new StackPanel
        {
            Orientation = Avalonia.Layout.Orientation.Horizontal,
            Spacing = 2,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        pair.Children.Add(Frame(HorizontalStripes(width * 0.36, height * 0.58,
            "#F7F7F4", "#477CC0", "#C83C4A"), width * 0.36, height * 0.58));
        pair.Children.Add(DeyttTheme.TextBlock("→", 8, DeyttTheme.Muted,
            FontWeight.SemiBold, wrap: false));
        pair.Children.Add(Frame(HorizontalStripes(width * 0.36, height * 0.58,
            "#17191E", "#C83C4A", "#F0C66B"), width * 0.36, height * 0.58));
        return pair;
    }

    private static Control Fallback(string code, double width, double height)
    {
        var label = code switch
        {
            "AUTO" => "✦",
            "IP" => "IP",
            "—" or "" => "•",
            _ => code,
        };
        var text = DeyttTheme.TextBlock(label, code.Length <= 2 ? 10 : 9,
            DeyttTheme.Sky, FontWeight.SemiBold, DeyttTheme.JetBrainsMono, wrap: false);
        text.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        text.TextAlignment = TextAlignment.Center;
        return new Border
        {
            Width = width - 2,
            Height = height - 2,
            Background = DeyttTheme.Brush(DeyttTheme.Surface2),
            Child = text,
        };
    }

    private static Border Frame(Control content, double width, double height) => new()
    {
        Width = width,
        Height = height,
        CornerRadius = new CornerRadius(5),
        Background = DeyttTheme.Brush(DeyttTheme.Surface2),
        BorderBrush = DeyttTheme.Brush(DeyttTheme.Line),
        BorderThickness = new Avalonia.Thickness(1),
        ClipToBounds = true,
        Child = content,
        VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
    };

    private static string FlagName(string code) => code switch
    {
        "NL" => "Флаг Нидерландов",
        "DE" => "Флаг Германии",
        "RU" => "Флаг России",
        "FI" => "Флаг Финляндии",
        "RU-DE" => "Флаги России и Германии",
        "AUTO" => "Автоматический выбор маршрута",
        "IP" => "Регион, определённый по IP",
        _ => "Регион не указан",
    };
}
