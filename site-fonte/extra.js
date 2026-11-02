<script>
/* Projetor: escolha de método, endereço curto e copiar o texto do Claude Code. */
(function(){
  'use strict';
  var origin = location.protocol.indexOf('http') === 0 ? location.origin : 'https://ENDERECO-DO-SITE';
  var host = origin.replace(/^https?:\/\//, '');

  // Endereco curto para digitar no navegador do projetor.
  Array.prototype.forEach.call(document.querySelectorAll('[data-short-url]'), function (el) { el.textContent = host + '/apk'; });

  // Texto pronto do Claude Code, com o endereco real deste site.
  var pre = document.getElementById('prompt-text');
  var tpl = document.getElementById('prompt-tpl');
  if (pre && tpl) pre.textContent = tpl.textContent.trim().replace(/__ORIGEM__/g, origin);

  // Botoes que abrem um metodo: ativam a aba e rolam ate ela.
  Array.prototype.forEach.call(document.querySelectorAll('[data-goto]'), function (b) {
    b.addEventListener('click', function () {
      var tab = document.getElementById(b.getAttribute('data-goto'));
      if (!tab) return;
      tab.click();
      document.getElementById('instalar').scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
      setTimeout(function () { tab.focus({ preventScroll: true }); }, 400);
    });
  });


  // Bolinhas embaixo dos passos que deslizam no celular (mostram em qual cartao esta).
  Array.prototype.forEach.call(document.querySelectorAll('.steps'), function (list) {
    var items = list.querySelectorAll('.step');
    if (items.length < 2) return;
    var dots = document.createElement('div');
    dots.className = 'dots'; dots.setAttribute('aria-hidden', 'true');
    for (var i = 0; i < items.length; i++) dots.appendChild(document.createElement('span'));
    list.parentNode.insertBefore(dots, list.nextSibling);
    function update() {
      if (!list.clientWidth) return; // aba ou painel escondido: calcula quando aparecer
      var w = items[0].getBoundingClientRect().width || 1;
      var idx = Math.round(list.scrollLeft / (w + 12));
      if (list.scrollLeft + list.clientWidth >= list.scrollWidth - 4) idx = items.length - 1;
      for (var k = 0; k < dots.children.length; k++) dots.children[k].className = k === idx ? 'on' : '';
    }
    list.addEventListener('scroll', update, { passive: true });
    window.addEventListener('resize', update);
    // Troca de aba ou abrir o "Como ligar o ADB": recalcula quando o conteudo aparece.
    document.addEventListener('click', function (e) {
      if (e.target.closest && e.target.closest('[role=tab],.acc__trigger,[data-goto],[data-open-adb]')) setTimeout(update, 60);
    });
    update();
  });

  // "Como ligar" abre o passo a passo do ADB.
  Array.prototype.forEach.call(document.querySelectorAll('[data-open-adb]'), function (a) {
    a.addEventListener('click', function () {
      var t = document.querySelector('#adb .acc__trigger');
      if (t && t.getAttribute('aria-expanded') !== 'true') t.click();
    });
  });

  Array.prototype.forEach.call(document.querySelectorAll('[data-copy]'), function (b) {
    var label = b.textContent;
    b.addEventListener('click', function () {
      var src = document.getElementById(b.getAttribute('data-copy'));
      var text = src ? src.textContent : '';
      var done = function () { b.textContent = 'Copiado'; setTimeout(function () { b.textContent = label; }, 2000); };
      if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).then(done, fallback);
      } else fallback();
      function fallback() {
        var r = document.createRange(); r.selectNodeContents(src);
        var s = getSelection(); s.removeAllRanges(); s.addRange(r);
        try { document.execCommand('copy'); s.removeAllRanges(); done(); } catch (e) { b.textContent = 'Selecionado: copie'; }
      }
    });
  });

  // Lógica do Seletor de Modelo para Download Direto
  var modelSelect = document.getElementById('model-select');
  var downloadBtn = document.getElementById('download-btn');
  var downloadHint = document.getElementById('download-hint');

  if (modelSelect && downloadBtn) {
    modelSelect.addEventListener('change', function () {
      var val = this.value;
      if (!val) {
        downloadBtn.style.opacity = '0.5';
        downloadBtn.style.pointerEvents = 'none';
        downloadBtn.href = '#';
        downloadBtn.onclick = function() { return false; };
        if (downloadHint) downloadHint.textContent = '';
        return;
      }

      downloadBtn.style.opacity = '1';
      downloadBtn.style.pointerEvents = 'auto';
      downloadBtn.onclick = null; // Remove o bloqueio

      // HY320, TX6, H96 -> Força Compat (Sem SharedID)
      // HY300, Outro -> Tenta Shared (Com SharedID) primeiro, ou Compat se falhar (mas aqui direcionamos direto)
      if (val === 'hy320' || val === 'tx6') {
        downloadBtn.href = '/apk-compat';
        downloadBtn.textContent = 'Baixar APK (Compatível)';
        if (downloadHint) downloadHint.textContent = 'Versão segura para Android 12+ restrito.';
      } else {
        // Para HY300 e outros, oferecemos a Shared (Controle Total)
        // Nota: Se falhar, o usuário pode voltar e pegar a Compat.
        downloadBtn.href = '/apk';
        downloadBtn.textContent = 'Baixar APK (Controle Total)';
        if (downloadHint) downloadHint.textContent = 'Versão com controle completo (pode falhar em alguns Android 12+).';
      }
    });
  }
})();
</script>
