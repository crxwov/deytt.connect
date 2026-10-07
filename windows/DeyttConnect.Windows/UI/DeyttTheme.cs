using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;

namespace DeyttConnect.Windows.UI;

internal static class DeyttTheme
{
    public static readonly Color Background = Color.Parse("#080B12");
    public static readonly Color Surface = Color.Parse("#111823");
    public static readonly Color Surface2 = Color.Parse("#192331");
    public static readonly Color Line = Color.Parse("#283548");
    public static readonly Color Text = Color.Parse("#F2F5FC");
    public static readonly Color Muted = Color.Parse("#9AA8BC");
    public static readonly Color Blue = Color.Parse("#8494FF");
    public static readonly Color BlueDeep = Color.Parse("#5D6CF0");
    public static readonly Color Sky = Color.Parse("#6BDDF2");
    public static readonly Color Mint = Color.Parse("#63E0B4");
    public static readonly Color Coral = Color.Parse("#FF8295");
    public static readonly Color Amber = Color.Parse("#FFC76E");
    public static readonly Color MapSurface = Color.Parse("#0A1018");
    public static readonly Color MapLine = Color.Parse("#202B38");
    public static readonly Color Selected = Color.Parse("#1C2940");
    public static readonly Color SelectedLine = Color.Parse("#435A87");
    public static readonly Color BlueSurface = Color.Parse("#242C4A");
    public static readonly Color SkySurface = Color.Parse("#1B303D");
    public static readonly Color MintSurface = Color.Parse("#18342E");

    public static FontFamily InterTight { get; } = new("fonts:DEYTT#Inter Tight");
    public static FontFamily Unbounded { get; } = new("fonts:DEYTT#Unbounded");
    public static FontFamily JetBrainsMono { get; } = new("fonts:DEYTT#JetBrains Mono");

    public static SolidColorBrush Brush(Color color) => new(color);

    public static TextBlock TextBlock(
        string value,
        double size = 16,
        Color? color = null,
        FontWeight? weight = null,
        FontFamily? family = null,
        bool wrap = true)
    {
        return new TextBlock
        {
            Text = value.ToLowerInvariant(),
            FontFamily = family ?? InterTight,
            FontSize = size,
            FontWeight = weight ?? FontWeight.Normal,
            Foreground = Brush(color ?? Text),
            TextWrapping = wrap ? TextWrapping.Wrap : TextWrapping.NoWrap,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
    }

    public static Border Card(
        Control child,
        Color? fill = null,
        Color? stroke = null,
        double radius = 22,
        Thickness? padding = null)
    {
        return new Border
        {
            Background = Brush(fill ?? Surface),
            BorderBrush = Brush(stroke ?? Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(radius),
            Padding = padding ?? new Thickness(18),
            Child = child,
        };
    }

    public static Button Action(Control child, Action onClick)
    {
        var button = new Button
        {
            Content = child,
            Background = Brush(Colors.Transparent),
            BorderBrush = Brush(Colors.Transparent),
            BorderThickness = new Thickness(0),
            Padding = new Thickness(0),
            HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
        button.Classes.Add("deytt-button");
        button.Click += (_, _) => onClick();
        return button;
    }

    public static Border PrimaryButton(string label, Action onClick)
    {
        var labelText = TextBlock(label, 16, Background, FontWeight.SemiBold, wrap: false);
        labelText.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        labelText.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        labelText.TextAlignment = TextAlignment.Center;
        var button = Action(
            labelText,
            onClick);
        button.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        button.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch;
        return new Border
        {
            Height = 56,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
            Background = new LinearGradientBrush
            {
                StartPoint = new RelativePoint(0, 0.5, RelativeUnit.Relative),
                EndPoint = new RelativePoint(1, 0.5, RelativeUnit.Relative),
                GradientStops =
                {
                    new GradientStop(Sky, 0),
                    new GradientStop(Mint, 1),
                },
            },
            CornerRadius = new CornerRadius(17),
            Child = button,
        };
    }

    public static Border IconTile(string glyph, double size = 56, Color? color = null)
    {
        var icon = TextBlock(glyph, size <= 44 ? 18 : 22,
            color ?? Sky, FontWeight.SemiBold, JetBrainsMono, wrap: false);
        icon.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        icon.TextAlignment = TextAlignment.Center;
        return new Border
        {
            Width = size,
            Height = size,
            Background = Brush(Surface2),
            BorderBrush = Brush(Line),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(17),
            Child = icon,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        };
    }

    public static Border Spacer(double height) => new() { Height = height };

    public static TextBlock SectionLabel(string label) => TextBlock(
        label.ToLowerInvariant(), 11, Muted, FontWeight.SemiBold, JetBrainsMono, wrap: false);

    public static Border Hairline(double left = 0) => new()
    {
        Height = 1,
        Margin = new Thickness(left, 0, 0, 0),
        Background = Brush(Line),
    };
}
