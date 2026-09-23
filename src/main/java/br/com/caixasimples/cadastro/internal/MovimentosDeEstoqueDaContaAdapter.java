package br.com.caixasimples.cadastro.internal;

import br.com.caixasimples.contas.MovimentosDeEstoqueDaConta;
import org.springframework.stereotype.Component;

/** Responde pelo histórico sem expor o repositório interno do cadastro a Contas. */
@Component
class MovimentosDeEstoqueDaContaAdapter implements MovimentosDeEstoqueDaConta {

    private final ProdutoRepository produtos;

    MovimentosDeEstoqueDaContaAdapter(ProdutoRepository produtos) {
        this.produtos = produtos;
    }

    @Override
    public boolean existem() {
        return produtos.existsByMovimentosIsNotEmpty();
    }
}
