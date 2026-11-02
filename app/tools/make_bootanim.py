"""Gera bootanimation.zip (1280x720): fundo preto, "M SOLUCTION" em Inter e "Loading N%" embaixo.

O Android 11 nao informa o progresso real do boot para a animacao, entao a porcentagem segue o
tempo tipico de ligar (cerca de 20 s): sobe ate 99% e fica nisso ate o sistema terminar.
O zip fica sem compressao (exigido pelo bootanimation). Instalar em /oem/media/bootanimation.zip.
Uso: python -I tools/make_bootanim.py build/bootanimation.zip
"""
import io
import sys
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

W, H, FPS = 1280, 720, 5
ROOT = Path(__file__).resolve().parent.parent
FONT = ROOT / 'assets' / 'fonts' / 'Inter.ttf'
MARCA = 'M SOLUCTION'


def fonte(px, peso):
    f = ImageFont.truetype(str(FONT), px)
    try:
        f.set_variation_by_axes([28, peso])  # opsz, wght
    except Exception:
        pass
    return f


TITULO = fonte(52, 650)
TEXTO = fonte(20, 500)
# Posicao fixa: o texto nao dança quando a porcentagem ganha um digito.
TEXTO_X = (W - TEXTO.getbbox('Loading 100%')[2]) // 2


def frame(percent):
    img = Image.new('RGB', (W, H), 0)
    d = ImageDraw.Draw(img)
    caixa = TITULO.getbbox(MARCA)
    d.text(((W - caixa[2]) // 2, H // 2 - 56), MARCA, fill=(255, 255, 255), font=TITULO)
    d.text((TEXTO_X, H // 2 + 24), f'Loading {percent}%', fill=(150, 150, 156), font=TEXTO)
    return img


def jpg(img):
    b = io.BytesIO()
    img.save(b, 'JPEG', quality=90)
    return b.getvalue()


def main(out):
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_STORED) as z:
        z.writestr('desc.txt', f'{W} {H} {FPS}\np 1 0 part0\np 0 0 part1\n')
        for i in range(100):
            z.writestr(f'part0/{i:03d}.jpg', jpg(frame(i)))
        z.writestr('part1/000.jpg', jpg(frame(99)))
    frame(42).save(Path(out).with_suffix('.preview.png'))
    print(out, Path(out).stat().st_size // 1024, 'KB')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'build/bootanimation.zip')
