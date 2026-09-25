package br.com.caixasimples.sincronizacao;

import java.util.Set;

/**
 * O que um módulo sabe fazer com os gestos que o dispositivo registrou sem rede.
 *
 * <p><strong>Declarada aqui, implementada pelo módulo dono do registro</strong>, no
 * {@code internal} dele: o cadastro aplica os gestos de Produto e de Cliente, o caixa os da
 * SessaoCaixa, vendas os da Venda. O sentido é deliberado, o mesmo da pergunta que a venda faz ao
 * caixa: quem precisa da operação declara a assinatura, e o dono a cumpre com os próprios casos de
 * uso, sem expor o pacote deles a ninguém. Este módulo não conhece gesto nenhum; conhece a ordem,
 * a transação e o registro do resultado.
 *
 * <p><strong>Roda dentro da transação que o lote abriu para a operação.</strong> Os casos de uso
 * chamados aqui participam dela, e o resultado da operação é gravado nela depois que este método
 * volta. Se o método lançar, nada do que ele fez fica.
 *
 * <p><strong>Recusa é exceção.</strong> O gesto que não se cumpre por regra, venda fechada, produto
 * inativo, valor inválido, termina com a exceção da própria regra, argumento ou estado inválido,
 * acesso negado, ou com {@link OperacaoRecusadaException} quando a exceção do módulo não é nenhuma
 * dessas, como o registro que não existe nesta conta. Quem chama grava a recusa com a mensagem.
 * Falha de banco não deve ser traduzida: ela é o que o dispositivo repete.
 */
public interface AplicadorDeOperacoes {

    /** Os tipos de gesto que este módulo aplica, exatamente como o dispositivo os grava. */
    Set<String> tipos();

    /**
     * Aplica o gesto pelos casos de uso do módulo.
     *
     * @return a revisão do registro depois do gesto, quando ele tem revisão, e o motivo de revisão
     *         para o administrador, quando o gesto foi aplicado com pendência
     * @throws OperacaoRecusadaException quando uma regra do módulo recusa o gesto
     */
    Aplicacao aplicar(OperacaoRecebida operacao);
}
