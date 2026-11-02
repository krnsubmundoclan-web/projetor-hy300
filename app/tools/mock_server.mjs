// Servidor local de teste da pagina do celular, com a API simulada (nao fala com o projetor).
// Uso: node tools/mock_server.mjs  ->  http://localhost:8099  (qualquer PIN entra)
//   /?demo=1[&aba=send][&sheet=voice|keys|settings]  entra direto (PIN de teste) para capturas de tela
//   /frame?<mesmos parametros>                       a pagina numa moldura de iPhone (390x844)
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'assets');
const apps = [
  ['com.google.android.youtube.tv', 'YouTube', '#FF3B30'], ['com.netflix.mediaclient', 'Netflix', '#1C1C1E'],
  ['com.disney.disneyplus', 'Disney+', '#0A3D91'], ['com.amazon.amazonvideo.livingroom', 'Prime Video', '#00A8E1'],
  ['com.globo.globotv', 'Globoplay', '#FF2D55'], ['com.spotify.tv.android', 'Spotify', '#1DB954'],
  ['com.espn.score_center', 'ESPN', '#FF9500'], ['tv.aovivo', 'TV ao vivo', '#5E5CE6'],
].map(([pkg, nome, cor], fav) => ({ pkg, nome, cor, fav }));

createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  const json = (o) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(o)); };
  if (url.pathname === '/frame') {
    res.writeHead(200, { 'content-type': 'text/html' });
    return res.end(`<body style="margin:0;background:#000"><iframe src="/${url.search}" style="width:390px;height:844px;border:0;display:block"></iframe></body>`);
  }
  if (url.pathname === '/') {
    let html = readFileSync(join(root, 'web/index.html'), 'utf8');
    if (url.searchParams.get('demo')) {
      const aba = url.searchParams.get('aba'), sheet = url.searchParams.get('sheet');
      html = html.replace('<head>', "<head><script>localStorage.setItem('pin','0000');localStorage.setItem('dica','" + (url.searchParams.get('dica') ? '' : '1') + "')</script>");
      const extra = (aba ? `document.querySelector('[data-tab=${aba}]').click();` : '') + (sheet ? `document.getElementById('${sheet}').classList.add('show');` : '');
      if (extra) html = html.replace('</body>', `<script>setTimeout(()=>{${extra}},400)</script></body>`);
    }
    res.writeHead(200, { 'content-type': 'text/html' });
    return res.end(html);
  }
  if (url.pathname === '/font.ttf') return res.end(readFileSync(join(root, 'fonts/Inter.ttf')));
  if (url.pathname === '/icon.png') { res.writeHead(200, { 'content-type': 'image/png' }); return res.end(readFileSync(join(root, 'web/icon.png'))); }
  if (url.pathname === '/api/ola') return json({ ok: true, token: null });
  if (url.pathname === '/api/aparelho/salvar') return json({ ok: true, token: 'd' + '0'.repeat(48) });
  if (url.pathname === '/api/listas') return json({ ok: true, listas: [] });
  if (url.pathname === '/api/transmitir') return json({ ok: true, lista: ['iPhone, iPad e Mac (AirPlay)', 'Android e Windows (Miracast)'] });
  if (url.pathname === '/api/personalizar') return json({ ok: true, foto: false, cor: 0, cores: ['Padrão', 'Grafite', 'Azul-noite', 'Vinho', 'Verde-floresta', 'Preto'], escurecer: 1, escurecerOp: ['Leve', 'Médio', 'Forte'], descanso: 1, descansoCor: 0, descansoOp: ['Padrão do sistema', 'Igual ao fundo', 'Outra foto', 'Cor sólida'],
    aneis: [['temp', 'Temperatura'], ['cpu', 'CPU'], ['ram', 'RAM'], ['espaco', 'Espaço'], ['internet', 'Internet']].map(([id, nome]) => ({ id, nome, on: true })) });
  if (url.pathname === '/api/ping') return json({ ok: true, auth: true });
  if (url.pathname === '/api/state') return json({ ok: true, tipo: 'tela', app: 'com.netflix.mediaclient', nome: 'Netflix', perfil: 'video', foco: 'Stranger Things', volume: 64, mudo: false, escuro: true });
  if (url.pathname === '/api/apps') return json(apps);
  if (url.pathname === '/api/icon') { res.writeHead(404); return res.end(); }
  if (url.pathname.startsWith('/api/')) return json({ ok: true });
  res.writeHead(404); res.end();
}).listen(8099, () => console.log('mock em http://localhost:8099'));
