// Dia no horario de Brasilia (AAAA-MM-DD), usado pelo contador e pelo admin.
export function diaBrasil(d = new Date()) {
  return d.toLocaleDateString('en-CA', { timeZone: 'America/Sao_Paulo' });
}
