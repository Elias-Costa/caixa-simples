package br.com.caixasimples.cadastro;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.caixasimples.TesteDeIntegracao;
import br.com.caixasimples.cadastro.application.ProdutoService;
import br.com.caixasimples.contas.AutenticadorDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste;
import br.com.caixasimples.contas.CriadorDeContaDeTeste.ContaCriada;
import br.com.caixasimples.contas.internal.ContaRepository;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** O primeiro login de ADMIN copia uma vez só, com a marca e os itens na mesma Conta (RF32). */
class PrimeiroAcessoDaContaTest extends TesteDeIntegracao {

    private static final String SENHA = "uma senha longa de teste";

    @Autowired CriadorDeContaDeTeste criador;
    @Autowired AutenticadorDeTeste autenticador;
    @Autowired ProdutoService produtos;
    @Autowired ContaRepository contas;

    @Test
    void primeiroLoginAplicaCatalogoUmaVezEIsolaAsContas() {
        ContaCriada contaA = criador.criarComTipo("Cafeteria A", "Cafeteria", SENHA);
        ContaCriada contaB = criador.criarComTipo("Cafeteria B", "cafeteria", SENHA);

        assertThat(contaA.comoUsuario(produtos::listarAtivos)).isEmpty();
        assertThat(contas.findById(contaA.contaId().valor()).orElseThrow()
                .isCatalogoInicialAplicado()).isFalse();

        autenticador.tokenDe(contaA);
        Set<String> idsA = contaA.comoUsuario(() -> produtos.listarAtivos().stream()
                .map(produto -> produto.getId().toString()).collect(Collectors.toSet()));
        assertThat(idsA).hasSize(8);
        assertThat(contaA.comoUsuario(produtos::listarAtivos))
                .allSatisfy(produto -> assertThat(produto.getPreco().valor())
                        .isEqualByComparingTo("0.00"));
        assertThat(contaB.comoUsuario(produtos::listarAtivos)).isEmpty();
        assertThat(contas.findById(contaA.contaId().valor()).orElseThrow()
                .isCatalogoInicialAplicado()).isTrue();

        autenticador.tokenDe(contaA);
        assertThat(contaA.comoUsuario(() -> produtos.listarAtivos().stream()
                .map(produto -> produto.getId().toString()).collect(Collectors.toSet())))
                .isEqualTo(idsA);

        autenticador.tokenDe(contaB);
        Set<String> idsB = contaB.comoUsuario(() -> produtos.listarAtivos().stream()
                .map(produto -> produto.getId().toString()).collect(Collectors.toSet()));
        assertThat(idsB).hasSize(8).doesNotContainAnyElementsOf(idsA);
    }

    @Test
    void tipoSemModeloMarcaAContaEContinuaEmBranco() {
        ContaCriada conta = criador.criarComTipo("Oficina", "oficina", SENHA);
        autenticador.tokenDe(conta);
        assertThat(conta.comoUsuario(produtos::listarAtivos)).isEmpty();
        assertThat(contas.findById(conta.contaId().valor()).orElseThrow()
                .isCatalogoInicialAplicado()).isTrue();
    }
}
