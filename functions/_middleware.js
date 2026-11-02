// Contador: soma 1 por dia para cada download, visita ao site e checagem de atualizacao.
// Guarda so contagens agregadas (dia + o que foi), nada que identifique quem acessou.
// A gravacao roda depois da resposta (waitUntil), entao nao atrasa o download.
import { diaBrasil } from '../lib/dia.js';

const CHAVES = {
  '/': 'visita',
  '/index.html': 'visita',
  // Os arquivos ficam no GitHub Releases; conta quem passa pelo redirecionamento daqui.
  '/apk': 'projetor',
  '/apk-compat': 'projetor',
  '/app': 'instalador',
  '/kit': 'kit',
  '/version.json': 'checagem',
};


function chaveDe(url) {
  const p = url.pathname;
  if (CHAVES[p]) return CHAVES[p];
  return null;
}

export async function onRequest(context) {
  const { request, env } = context;
  const resp = await context.next();
  try {
    const chave = request.method === 'GET' && (resp.status === 200 || resp.status === 302) ? chaveDe(new URL(request.url)) : null;
    // Gerenciador de download pedindo pedacos do arquivo: conta so o primeiro pedaco.
    const range = request.headers.get('Range');
    if (chave && env.DB && (!range || /^bytes=0-/.test(range))) {
      context.waitUntil(
        env.DB.prepare('INSERT INTO hits (dia, chave, n) VALUES (?, ?, 1) ON CONFLICT(dia, chave) DO UPDATE SET n = n + 1')
          .bind(diaBrasil(), chave).run().catch(() => {})
      );
    }
  } catch (e) {
    // Contador nunca derruba o site.
  }
  return resp;
}
