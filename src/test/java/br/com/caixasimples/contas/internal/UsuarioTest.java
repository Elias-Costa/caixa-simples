package br.com.caixasimples.contas.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import br.com.caixasimples.shared.Perfil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A correção do nome na raiz {@link Usuario}: o nome removido a pedido não volta, nem pela
 * correção nem pelo marcador escrito à mão.
 *
 * <p>Teste de unidade puro. O caso de uso só chega aqui depois do perfil e da Conta, então a raiz é a
 * última linha da regra, e cada recusa deixa o nome como estava.
 */
class UsuarioTest {

    @Test
    @DisplayName("o nome do Usuário ativo e o do inativo são corrigidos, sem os espaços das pontas")
    void corrigeAtivoEInativo() {
        Usuario ativo = new Usuario("Joana", Perfil.OPERADOR);
        ativo.corrigirNome("  Joana Lima  ");
        assertThat(ativo.getNome()).isEqualTo("Joana Lima");

        Usuario inativo = new Usuario("Carlos", Perfil.OPERADOR);
        inativo.inativar();
        inativo.corrigirNome("Carlos Souza");
        assertThat(inativo.getNome()).isEqualTo("Carlos Souza");
        assertThat(inativo.isAtivo()).isFalse();
    }

    @Test
    @DisplayName("o nome removido a pedido não é corrigido, e continua removido")
    void nomeRemovidoNaoVolta() {
        Usuario usuario = new Usuario("Joana", Perfil.OPERADOR);
        usuario.inativar();
        usuario.anonimizarNome();

        assertThatIllegalStateException()
                .isThrownBy(() -> usuario.corrigirNome("Joana"))
                .withMessage("O nome deste usuário foi removido a pedido e não pode ser corrigido.");
        assertThat(usuario.isNomeRemovido()).isTrue();
        assertThat(usuario.getNome()).isEqualTo("Usuário removido");
    }

    @Test
    @DisplayName("o marcador de removido, em branco ou acima de 120 caracteres não servem de nome, e o nome fica")
    void nomeNovoRecusado() {
        Usuario usuario = new Usuario("Joana", Perfil.ADMIN);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> usuario.corrigirNome(" Usuário removido "))
                .withMessage("Esse é o texto que marca um usuário removido; escolha outro nome.");
        assertThatIllegalArgumentException().isThrownBy(() -> usuario.corrigirNome("   "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> usuario.corrigirNome("a".repeat(121)))
                .withMessageContaining("120");

        assertThat(usuario.getNome()).isEqualTo("Joana");
        assertThat(usuario.isNomeRemovido()).isFalse();
        usuario.corrigirNome("a".repeat(120));
        assertThat(usuario.getNome()).hasSize(120);
    }
}
