-- Contagens agregadas por dia (sem IP nem nada que identifique quem acessou).
CREATE TABLE IF NOT EXISTS hits (
  dia   TEXT NOT NULL,   -- AAAA-MM-DD no horario de Brasilia
  chave TEXT NOT NULL,   -- visita, projetor, instalador, kit, atualizacao, checagem
  n     INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (dia, chave)
);
