-- Conferência de uma cópia do banco: quantas linhas cada tabela tem e quanto dinheiro cada dia
-- movimentou. Roda na origem, na hora da cópia, e no banco restaurado; as duas saídas têm de ser
-- idênticas, linha por linha.
--
-- Tudo o que pode variar de um servidor para outro sem que o dado mude fica fixo aqui: a ordem
-- por colação C, a data por to_char e o dia no fuso America/Bahia, o mesmo que delimita o dia de
-- operação no sistema.

\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset fieldsep ' '

-- Uma contagem por tabela, montada a partir do catálogo: a tabela que uma migration nova criar
-- entra sozinha na conferência.
SELECT format('SELECT %L, count(*) FROM %I.%I', 'tabela ' || table_name, table_schema, table_name)
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name COLLATE "C"
\gexec

SELECT 'vendas concluidas',
       to_char(concluido_em AT TIME ZONE 'America/Bahia', 'YYYY-MM-DD'),
       count(*),
       sum(valor_total)
FROM venda
WHERE status = 'CONCLUIDA'
GROUP BY 2
ORDER BY 2;

SELECT 'caixa',
       to_char(criado_em AT TIME ZONE 'America/Bahia', 'YYYY-MM-DD'),
       tipo,
       count(*),
       sum(valor)
FROM movimento_caixa
GROUP BY 2, 3
ORDER BY 2, tipo COLLATE "C";
