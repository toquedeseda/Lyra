"""Icono de Lyra para Windows (el mismo de la web y del móvil): PNG para la ventana y .ico para el instalador.

Uso: python desktop/icons/make_icons.py
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RESOURCES = os.path.join(HERE, "..", "src", "main", "resources", "icons")
BG = (10, 10, 11, 255)
FG = (232, 230, 223, 255)

# Formas del logotipo en el lienzo de 108 × 108 (icono.svg de la web).
RECTS = [(43, 32, 51, 72), (38, 31.5, 57, 34.3), (38, 72, 74, 75), (71.5, 63, 74.5, 75)]
CIRCLE = (68, 42, 3.2)


def render(size: int, zoom: float = 1.0) -> Image.Image:
    big = 1024
    scale = big / 108
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((0, 0, big - 1, big - 1), radius=int(26 * scale), fill=BG)

    def tx(x, y):
        # Acerca el dibujo al centro en los tamaños pequeños para que se lea.
        cx, cy = 54, 54
        return ((cx + (x - cx) * zoom) * scale, (cy + (y - cy) * zoom) * scale)

    for x0, y0, x1, y1 in RECTS:
        a = tx(x0, y0)
        b = tx(x1, y1)
        draw.rectangle((a[0], a[1], b[0], b[1]), fill=FG)
    cx, cy, r = CIRCLE
    c = tx(cx, cy)
    rr = r * zoom * scale
    draw.ellipse((c[0] - rr, c[1] - rr, c[0] + rr, c[1] + rr), fill=FG)
    return image.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(RESOURCES, exist_ok=True)
    images = {}
    for size in (16, 24, 32, 48, 64, 128, 256, 512):
        zoom = 1.35 if size <= 24 else 1.2 if size <= 48 else 1.0
        images[size] = render(size, zoom)
        if size in (32, 64, 128, 256, 512):
            images[size].save(os.path.join(RESOURCES, f"lyra-{size}.png"))
    ico_sizes = [16, 24, 32, 48, 64, 128, 256]
    images[256].save(os.path.join(HERE, "lyra.ico"), sizes=[(s, s) for s in ico_sizes], append_images=[images[s] for s in ico_sizes[:-1]])
    print("iconos listos")


if __name__ == "__main__":
    main()
