-- Outbox de eventos de domínio: o registro de publicação do Spring Modulith.
--
-- Toda publicação de evento que tem listener transacional é gravada aqui, na mesma transação de
-- quem publicou, e marcada como concluída quando o listener termina. É o que dá garantia de
-- entrega ao efeito colateral entre módulos: se o listener falhar, a linha fica incompleta e pode
-- ser reprocessada, em vez de o efeito se perder em silêncio.
--
-- A tabela é do framework, não do negócio: a entidade JPA que a mapeia vem do próprio Modulith
-- (JpaEventPublication), e este projeto só fornece o schema, porque o Hibernate roda em modo de
-- validação e o Modulith não distribui DDL para JPA. Por isso ela é a única tabela de dado
-- persistido sem conta_id e sem filtro de tenant: o registro pertence à infraestrutura, e a conta
-- a que o evento se refere viaja dentro do JSON serializado, de onde o listener a lê.
--
-- As colunas foram GERADAS a partir da entidade, nunca escritas de memória, para não adivinhar o
-- mapeamento interno do framework. Receita, para repetir numa atualização do Modulith:
--   spring.jpa.hibernate.ddl-auto=none
--   spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create
--   spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-source=metadata
--   spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=target/ddl-gerado.sql
-- num teste de integração descartável, e comparar o CREATE TABLE gerado com este arquivo.
--
-- Dois ajustes sobre o gerado, os dois tirados do schema oficial que o Modulith distribui para o
-- módulo JDBC no PostgreSQL: as colunas de texto são text, e não varchar(255), porque
-- serialized_event guarda o JSON do evento inteiro e listener_id guarda a assinatura completa do
-- método; e os dois índices abaixo, que a entidade não declara. timestamptz é o mesmo tipo que o
-- gerado timestamp(6) with time zone, escrito como nas migrations anteriores. O Hibernate valida
-- tipo, não tamanho, então text passa pela validação como varchar passaria.
CREATE TABLE event_publication (
    id                      uuid          PRIMARY KEY,
    listener_id             text          NOT NULL,
    event_type              text          NOT NULL,
    serialized_event        text          NOT NULL,
    publication_date        timestamptz   NOT NULL,
    completion_date         timestamptz,
    status                  varchar(255),
    completion_attempts     integer       NOT NULL,
    last_resubmission_date  timestamptz,

    -- Gerado do enum do framework. Numa atualização do Modulith, regenerar e comparar.
    CONSTRAINT event_publication_status_valido
        CHECK (status IN ('PUBLISHED', 'PROCESSING', 'COMPLETED', 'FAILED', 'RESUBMITTED'))
);

-- Nomes do schema oficial do Modulith, para a comparação com a documentação dele ser direta.
-- O índice hash sustenta a conclusão da publicação, que a localiza pelo evento serializado e
-- pelo listener; o de completion_date sustenta a busca de publicações incompletas.
CREATE INDEX event_publication_serialized_event_hash_idx
    ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx
    ON event_publication (completion_date);

COMMENT ON TABLE  event_publication IS
    'Outbox do Spring Modulith: uma linha por par evento e listener, gravada na transação de quem publicou e concluída quando o listener termina. Tabela do framework, sem conta_id: a conta viaja dentro do evento serializado.';
COMMENT ON COLUMN event_publication.serialized_event IS
    'O evento em JSON. É daqui que uma reentrega remonta o evento, então o tipo dele precisa ir e voltar do JSON sem perda.';
COMMENT ON COLUMN event_publication.completion_date IS
    'Nula enquanto o listener não terminou. Uma linha antiga com esta coluna nula é um efeito colateral que não aconteceu.';
