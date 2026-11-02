"""Monta site/index.html a partir de base.html + body.html, extra.css, extra.js, prompt.txt e o QR do Pix.
Uso (na raiz do repositorio): python site-fonte/montar.py   (precisa de app/build/ compilado para o SHA-256)
"""
import hashlib
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(HERE), 'site')
VERSAO = "5.0.0"


def read(name):
    return open(os.path.join(HERE, name), encoding='utf-8').read()


BUILD = os.path.join(os.path.dirname(HERE), 'app', 'build')
sha = hashlib.sha256(open(os.path.join(BUILD, 'Projetor-sistema.apk'), 'rb').read()).hexdigest()
sha_compat = hashlib.sha256(open(os.path.join(BUILD, 'Projetor-compat.apk'), 'rb').read()).hexdigest()
s = read('base.html')
desc = 'Tela inicial nova e controle pelo celular para projetores, TV Boxes e Smart TVs. Como instalar, usar e remover.'
for o, n in [('<title>TÍTULO DA PÁGINA — MLAB</title>', '<title>M Soluction — Projetores, TV Boxes e Smart TVs</title>'),
             ('<meta name="description" content="DESCRIÇÃO EM ATÉ 155 CARACTERES.">', '<meta name="description" content="%s">' % desc),
             ('<meta property="og:title" content="TÍTULO DA PÁGINA — MLAB">', '<meta property="og:title" content="M Soluction — Projetores, TV Boxes e Smart TVs">'),
             ('<meta property="og:description" content="DESCRIÇÃO EM ATÉ 155 CARACTERES.">', '<meta property="og:description" content="%s">' % desc),
             ('<meta property="og:type" content="website">', '<meta property="og:type" content="website">\n<meta property="og:image" content="passos/06-tela-inicial.webp">\n<meta name="author" content="M SOLUCTION">\n<meta property="og:site_name" content="M SOLUCTION">')]:
    assert s.count(o) == 1, o
    s = s.replace(o, n)
i = s.index('</style>')
s = s[:i] + read('extra.css') + s[i:]
a = s.index('<!-- ============ HEADER ============ -->')
b = s.index('<script>', a)
body = (read('body.html').replace('__SHA256__', sha).replace('__VERSAO__', VERSAO)
        .replace('__PIXCODE__', read('pix.txt').strip()).replace('__PIXSVG__', read('pix.svg')))
prompt = read('prompt.txt').replace('__SHA256__', sha).replace('__SHA256C__', sha_compat)
assert '</' not in prompt
s = s[:a] + body + '\n<script type="text/plain" id="prompt-tpl">\n' + prompt + '</script>\n' + s[b:]
i = s.rindex('</body>')
s = s[:i] + read('extra.js') + s[i:]
for t in ('__SHA256__', '__VERSAO__', '__PIXCODE__', '__PIXSVG__', 'PLACEHOLDER'):
    assert t not in s, t
open(os.path.join(OUT, 'index.html'), 'w', encoding='utf-8', newline='\n').write(s)
print('montado', len(s), 'bytes')
