using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;

namespace DeyttConnect.Windows.Controls;

public sealed class StarfieldBackground : Control
{
    private readonly Star[] _stars = CreateStars();

    public override void Render(DrawingContext context)
    {
        base.Render(context);
        if (Bounds.Width <= 0 || Bounds.Height <= 0)
            return;

        foreach (var star in _stars)
        {
            var tint = star.IsSky ? Color.Parse("#6BDDF2") : Colors.White;
            var brush = new SolidColorBrush(Color.FromArgb(star.Alpha, tint.R, tint.G, tint.B));
            var point = new Point(star.X * Bounds.Width, star.Y * Bounds.Height);
            context.DrawEllipse(brush, null, point, star.Radius, star.Radius);
            if (star.IsFlare)
            {
                var flare = new Pen(new SolidColorBrush(Color.FromArgb((byte)(star.Alpha * 0.58), tint.R, tint.G, tint.B)), 0.6);
                context.DrawLine(flare, new Point(point.X - 4, point.Y), new Point(point.X + 4, point.Y));
                context.DrawLine(flare, new Point(point.X, point.Y - 4), new Point(point.X, point.Y + 4));
            }
        }
    }

    private static Star[] CreateStars()
    {
        var stars = new Star[64];
        for (var index = 0; index < stars.Length; index++)
        {
            var random = new Random(0x44D7 + index * 7919);
            stars[index] = new Star(
                random.NextDouble(),
                random.NextDouble(),
                0.45 + random.NextDouble() * 1.15,
                (byte)(35 + random.Next(100)),
                index % 11 == 0,
                index % 19 == 4);
        }
        return stars;
    }

    private readonly record struct Star(double X, double Y, double Radius, byte Alpha, bool IsSky, bool IsFlare);
}
