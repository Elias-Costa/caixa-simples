package br.com.caixasimples.contas;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.internal.ClienteService;
import br.com.caixasimples.cadastro.internal.ClienteService.DadosDoCliente;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Executa o mesmo SQL do procedimento contra o PostgreSQL real do ensaio de integração. */
class EncerramentoDaContaSqlTest extends TesteDeIntegracao {

    @Autowired CriadorDeContaDeTeste criador;
    @Autowired ClienteService clientes;
    @Autowired JdbcTemplate banco;

    @Test
    void encerraUmaContaSemApagarAOutra() throws Exception {
        ContaCriada encerrada = criador.criar("Conta encerrada", "senha longa de teste");
        ContaCriada preservada = criador.criar("Conta preservada", "senha longa de teste");
        encerrada.comoUsuario(() -> clientes.cadastrar(new DadosDoCliente("Cliente antigo", null)));
        preservada.comoUsuario(() -> clientes.cadastrar(new DadosDoCliente("Cliente atual", null)));

        String sql = Files.readString(Path.of("operacao/exclusao/apagar-conta.sql"))
                .replace("\\set ON_ERROR_STOP on", "")
                .replace(":'conta_id'::uuid", "'" + encerrada.contaId() + "'::uuid");
        banco.execute(sql);

        assertThat(banco.queryForObject("SELECT count(*) FROM conta WHERE id = ?", Integer.class,
                encerrada.contaId().valor())).isZero();
        assertThat(banco.queryForObject("SELECT count(*) FROM conta WHERE id = ?", Integer.class,
                preservada.contaId().valor())).isEqualTo(1);
        for (String tabela : List.of("usuario", "credencial", "cliente", "produto", "sessao_caixa",
                "movimento_caixa", "venda", "item_venda", "pagamento", "recebimento",
                "movimento_estoque", "operacao_sincronizada")) {
            assertThat(banco.queryForObject("SELECT count(*) FROM " + tabela + " WHERE conta_id = ?",
                    Integer.class, encerrada.contaId().valor())).as(tabela).isZero();
        }
        assertThat(banco.queryForObject("SELECT count(*) FROM cliente WHERE conta_id = ?",
                Integer.class, preservada.contaId().valor())).isEqualTo(1);
    }

    @Test
    void restauracaoNaoTrazDeVoltaIdentidadeDoCliente() throws Exception {
        ContaCriada conta = criador.criar("Conta restaurada", "senha longa de teste");
        UUID clienteId = conta.comoUsuario(() -> clientes.cadastrar(
                new DadosDoCliente("Nome antigo", "contato@exemplo.test")));
        conta.comoUsuario(() -> clientes.remover(clienteId));

        // Simula uma cópia feita antes da remoção, ainda com o dado pessoal.
        banco.update("UPDATE cliente SET nome = 'Nome antigo', contato = 'contato@exemplo.test', "
                + "ativo = true, removido_em = NULL WHERE conta_id = ? AND id = ?",
                conta.contaId().valor(), clienteId);
        String sql = Files.readString(Path.of("operacao/copia/reaplicar-cliente.sql"))
                .replace("\\set ON_ERROR_STOP on", "")
                .replace(":'conta_id'::uuid", "'" + conta.contaId() + "'::uuid")
                .replace(":'registro_id'::uuid", "'" + clienteId + "'::uuid")
                .replace(":'instante'::timestamptz", "'2026-09-29T12:00:00Z'::timestamptz");
        banco.execute(sql);

        assertThat(banco.queryForMap("SELECT nome, contato, ativo, removido_em FROM cliente "
                + "WHERE conta_id = ? AND id = ?", conta.contaId().valor(), clienteId))
                .containsEntry("nome", "Cliente removido")
                .containsEntry("contato", null)
                .containsEntry("ativo", false);
    }
}
