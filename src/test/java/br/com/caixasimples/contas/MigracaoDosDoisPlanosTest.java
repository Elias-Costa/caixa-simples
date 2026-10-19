package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;

import br.com.caixasimples.TesteDeIntegracao;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A {@code V27}, que deixa dois planos, aplicada sobre um banco com linhas do plano intermediário,
 * que só um banco de desenvolvimento ou de ensaio anterior a ela tem.
 *
 * <p>O banco da suíte já nasce na última versão, sem essas linhas, então a prova roda num banco à
 * parte, no mesmo contêiner: o Flyway o leva até a {@code V26}, o teste grava as linhas antigas por
 * SQL, como estariam lá, e o Flyway aplica a {@code V27}.
 */
class MigracaoDosDoisPlanosTest extends TesteDeIntegracao {

    private static final UUID CONTA_INTERMEDIARIA = UUID.randomUUID();
    private static final UUID CONTA_GRATIS = UUID.randomUUID();
    private static final UUID CONTA_COMPLETA = UUID.randomUUID();
    private static final LocalDate VENCIMENTO_INTERMEDIARIO = LocalDate.of(2026, 11, 10);
    private static final LocalDate VENCIMENTO_COMPLETO = LocalDate.of(2026, 11, 5);

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private JdbcClient jdbcDaSuite;

    @Test
    @DisplayName("a V27 passa a Conta do plano intermediário ao Completo com o mesmo vencimento e apaga os pedidos dele e do upgrade")
    void migraOPlanoQueSai() {
        String nome = "migracao_v27_" + UUID.randomUUID().toString().replace("-", "");
        jdbcDaSuite.sql("CREATE DATABASE " + nome).update();
        try {
            String url = "jdbc:postgresql://" + postgres.getHost() + ":"
                    + postgres.getMappedPort(5432) + "/" + nome;
            migrarAte(url, "26");
            JdbcClient banco = JdbcClient.create(
                    new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));

            UUID adminIntermediario = conta(banco, CONTA_INTERMEDIARIA, "'CAIXA_SIMPLES', 10, '"
                    + VENCIMENTO_INTERMEDIARIO + "'");
            UUID adminGratis = conta(banco, CONTA_GRATIS, "'GRATIS', null, null");
            UUID adminCompleto = conta(banco, CONTA_COMPLETA, "'COMPLETO', 5, '"
                    + VENCIMENTO_COMPLETO + "'");

            pedido(banco, CONTA_INTERMEDIARIA, adminIntermediario, "'ADESAO', 'CAIXA_SIMPLES', 30,"
                    + " '2026-10-10', '2026-11-10', 'APLICADO', now(), '" + adminIntermediario + "'");
            pedido(banco, CONTA_INTERMEDIARIA, adminIntermediario, "'UPGRADE', 'COMPLETO', 12.90,"
                    + " '2026-10-19', '2026-11-10', 'ABERTO', null, null");
            pedido(banco, CONTA_GRATIS, adminGratis, "'ADESAO', 'CAIXA_SIMPLES', 30,"
                    + " null, null, 'ABERTO', null, null");
            UUID adesaoCompleta = pedido(banco, CONTA_COMPLETA, adminCompleto, "'ADESAO', 'COMPLETO',"
                    + " 70, '2026-10-05', '2026-11-05', 'APLICADO', now(), '" + adminCompleto + "'");
            UUID renovacaoAberta = pedido(banco, CONTA_COMPLETA, adminCompleto, "'RENOVACAO',"
                    + " 'COMPLETO', 70, '2026-11-05', '2026-12-05', 'ABERTO', null, null");

            migrarAte(url, "27");

            assertThat(banco.sql("SELECT id, plano, dia_de_vencimento, proximo_vencimento"
                            + " FROM conta")
                    .query((linha, n) -> tuple(linha.getObject("id", UUID.class),
                            linha.getString("plano"), linha.getObject("dia_de_vencimento"),
                            linha.getObject("proximo_vencimento", LocalDate.class)))
                    .list())
                    .as("a Conta paga fica no Completo com o mesmo vencimento, e as outras não mudam")
                    .containsExactlyInAnyOrder(
                            tuple(CONTA_INTERMEDIARIA, "COMPLETO", 10, VENCIMENTO_INTERMEDIARIO),
                            tuple(CONTA_GRATIS, "GRATIS", null, null),
                            tuple(CONTA_COMPLETA, "COMPLETO", 5, VENCIMENTO_COMPLETO));
            assertThat(banco.sql("SELECT id, situacao FROM pedido_de_plano")
                    .query((linha, n) -> tuple(linha.getObject("id", UUID.class),
                            linha.getString("situacao")))
                    .list())
                    .as("só os pedidos do Completo ficam, como estavam")
                    .containsExactlyInAnyOrder(
                            tuple(adesaoCompleta, "APLICADO"),
                            tuple(renovacaoAberta, "ABERTO"));

            assertThatExceptionOfType(DataIntegrityViolationException.class)
                    .isThrownBy(() -> banco.sql("UPDATE conta SET plano = 'CAIXA_SIMPLES'"
                                    + " WHERE id = :id")
                            .param("id", CONTA_COMPLETA)
                            .update())
                    .withMessageContaining("conta_plano_valido");
            assertThatExceptionOfType(DataIntegrityViolationException.class)
                    .isThrownBy(() -> pedido(banco, CONTA_COMPLETA, adminCompleto, "'RENOVACAO',"
                            + " 'CAIXA_SIMPLES', 30, '2026-12-05', '2027-01-05', 'SUBSTITUIDO',"
                            + " null, null"))
                    .withMessageContaining("pedido_de_plano_plano_pago");
            assertThatExceptionOfType(DataIntegrityViolationException.class)
                    .isThrownBy(() -> pedido(banco, CONTA_COMPLETA, adminCompleto, "'UPGRADE',"
                            + " 'COMPLETO', 10, '2026-11-10', '2026-12-05', 'SUBSTITUIDO',"
                            + " null, null"))
                    .withMessageContaining("pedido_de_plano_tipo_valido");
        } finally {
            jdbcDaSuite.sql("DROP DATABASE " + nome + " WITH (FORCE)").update();
        }
    }

    private void migrarAte(String url, String versao) {
        Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(versao)
                .load()
                .migrate();
    }

    /**
     * Uma Conta com o ADMIN que a criou, gravada por SQL como a versão anterior aceitava.
     *
     * @param planoECiclo o plano, o dia e o próximo vencimento, já como literais de SQL
     * @return o id do ADMIN
     */
    private static UUID conta(JdbcClient banco, UUID id, String planoECiclo) {
        banco.sql("INSERT INTO conta (id, nome_negocio, plano, dia_de_vencimento,"
                        + " proximo_vencimento) VALUES (:id, 'Loja da Esquina', " + planoECiclo + ")")
                .param("id", id)
                .update();
        UUID admin = UUID.randomUUID();
        banco.sql("INSERT INTO usuario (id, conta_id, nome, perfil) VALUES (:id, :conta, 'Ana',"
                        + " 'ADMIN')")
                .param("id", admin)
                .param("conta", id)
                .update();
        return admin;
    }

    /**
     * @param resto tipo, plano, valor, período, situação e aplicação, já como literais de SQL
     * @return o id do pedido
     */
    private static UUID pedido(JdbcClient banco, UUID conta, UUID criadoPor, String resto) {
        UUID id = UUID.randomUUID();
        banco.sql("INSERT INTO pedido_de_plano (id, conta_id, criado_em, criado_por, tipo, plano,"
                        + " valor, periodo_inicio, periodo_fim, situacao, aplicado_em, aplicado_por)"
                        + " VALUES (:id, :conta, now(), :criadoPor, " + resto + ")")
                .param("id", id)
                .param("conta", conta)
                .param("criadoPor", criadoPor)
                .update();
        return id;
    }
}
