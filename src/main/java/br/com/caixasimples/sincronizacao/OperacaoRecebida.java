package br.com.caixasimples.sincronizacao;

import br.com.caixasimples.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Um gesto como o dispositivo o gravou na fila local e o enviou.
 *
 * <p>Nenhum campo diz a conta nem quem opera: os dois vêm do token, e o servidor os fixa sem ler o
 * conteúdo do gesto (RNF05).
 *
 * <p>Os três métodos auxiliares existem para cada módulo transformar o conteúdo nos tipos dele
 * recusando o que não serve, em vez de deixar escapar uma exceção de leitura que o lote tomaria por
 * defeito do servidor.
 *
 * @param operacaoId o id do gesto, gerado no dispositivo; com a conta, é a chave de idempotência
 * @param registroId o Produto, Cliente, SessaoCaixa ou Venda que o gesto cria ou altera
 * @param tipo       o gesto, como {@code venda.concluir}
 * @param payload    o conteúdo, na forma que o módulo dono espera para aquele tipo
 * @param versaoBase a revisão do registro que o dispositivo leu antes de alterar; nula na criação
 * @param dependeDe  as operações que precisam ter resultado antes desta, sem repetição
 * @param criadoEm   o instante do gesto no relógio do dispositivo
 */
public record OperacaoRecebida(UUID operacaoId, UUID registroId, String tipo, JsonNode payload,
        Long versaoBase, List<UUID> dependeDe, Instant criadoEm) {

    public OperacaoRecebida {
        Objects.requireNonNull(operacaoId, "operacaoId nao pode ser nulo");
        Objects.requireNonNull(registroId, "registroId nao pode ser nulo");
        Objects.requireNonNull(tipo, "tipo nao pode ser nulo");
        Objects.requireNonNull(payload, "payload nao pode ser nulo");
        Objects.requireNonNull(criadoEm, "criadoEm nao pode ser nulo");
        dependeDe = dependeDe == null ? List.of() : List.copyOf(new LinkedHashSet<>(dependeDe));
    }

    /**
     * O conteúdo lido na forma que o módulo espera. Conteúdo que não é um objeto, ou que não tem
     * a forma do gesto, é recusa.
     */
    public <T> T payloadComo(ObjectMapper json, Class<T> forma) {
        if (!payload.isObject()) {
            throw new OperacaoRecusadaException("o conteudo do gesto " + tipo + " nao e um objeto");
        }
        try {
            return json.treeToValue(payload, forma);
        } catch (JacksonException falha) {
            throw new OperacaoRecusadaException(
                    "o conteudo do gesto " + tipo + " nao tem a forma esperada", falha);
        }
    }

    /** O campo obrigatório do conteúdo, ou a recusa dizendo qual faltou. */
    public <T> T exigir(T valor, String campo) {
        if (valor == null) {
            throw new OperacaoRecusadaException("o gesto " + tipo + " chegou sem " + campo);
        }
        return valor;
    }

    /** Um valor em dinheiro do conteúdo; fração de centavo é recusa, como na API com rede. */
    public Money dinheiro(BigDecimal valor, String campo) {
        exigir(valor, campo);
        try {
            return Money.de(valor);
        } catch (ArithmeticException fracaoDeCentavo) {
            throw new OperacaoRecusadaException("o campo " + campo + " do gesto " + tipo
                    + " tem fracao de centavo: " + valor, fracaoDeCentavo);
        }
    }
}
