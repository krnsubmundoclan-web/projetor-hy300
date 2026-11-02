// Metricas para a pagina /admin. Exige "Authorization: Bearer <ADMIN_TOKEN>" (segredo do Pages).
import { diaBrasil } from '../../../lib/dia.js';

const REPO = 'krnsubmundoclan-web/projetor-hy300';

function igual(a, b) {
  // Comparacao em tempo constante (nao revela o tamanho do acerto).
  const x = new TextEncoder().encode(a), y = new TextEncoder().encode(b);
  let d = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) d |= (x[i] || 0) ^ (y[i] || 0);
  return d === 0;
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Robots-Tag': 'noindex' },
  });
}

async function github() {
  try {
    const r = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=50`, {
      headers: { 'User-Agent': 'mlabsoluction-admin', Accept: 'application/vnd.github+json' },
      cf: { cacheTtl: 300 },
    });
    if (!r.ok) return null;
    const rel = await r.json();
    const porArquivo = {};
    const porVersao = [];
    for (const v of rel) {
      let tot = 0;
      for (const a of v.assets || []) {
        porArquivo[a.name] = (porArquivo[a.name] || 0) + a.download_count;
        tot += a.download_count;
      }
      porVersao.push({ versao: v.tag_name, downloads: tot });
    }
    return { porArquivo, porVersao };
  } catch (e) {
    return null;
  }
}

export async function onRequestGet({ request, env }) {
  const auth = request.headers.get('Authorization') || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : '';
  if (!env.ADMIN_TOKEN || !token || !igual(token, env.ADMIN_TOKEN)) return json({ ok: false, erro: 'chave inválida' }, 401);
  if (!env.DB) return json({ ok: false, erro: 'banco não configurado' }, 500);

  const hoje = diaBrasil();
  const inicio = diaBrasil(new Date(Date.now() - 29 * 86400000));
  const [totais, diario, gh] = await Promise.all([
    env.DB.prepare('SELECT chave, SUM(n) AS n FROM hits GROUP BY chave').all(),
    env.DB.prepare('SELECT dia, chave, n FROM hits WHERE dia >= ? ORDER BY dia').bind(inicio).all(),
    github(),
  ]);
  const t = {};
  for (const r of totais.results) t[r.chave] = r.n;
  return json({ ok: true, hoje, inicio, totais: t, diario: diario.results, github: gh });
}
