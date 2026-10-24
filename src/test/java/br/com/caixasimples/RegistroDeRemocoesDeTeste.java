package br.com.caixasimples;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.RegistroDeRemocoes;
import br.com.caixasimples.shared.RegistroDeRemocoesIndisponivelException;
import br.com.caixasimples.shared.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Memória compartilhada pelos testes para provar a gravação fora do banco. Fora do ar, recusa como
 * o bucket indisponível; quem desliga o registro o religa com {@link #limpar()} no fim do teste,
 * porque o mesmo objeto serve a todos.
 */
public class RegistroDeRemocoesDeTeste implements RegistroDeRemocoes {

    public record Remocao(ContaId conta, Tipo tipo, UUID registroId,
            Instant instante, UUID solicitadoPor) {
    }

    private final List<Remocao> registros = new CopyOnWriteArrayList<>();

    private volatile boolean foraDoAr;

    @Override
    public void registrar(Tipo tipo, UUID registroId, Instant instante, UUID solicitadoPor) {
        if (foraDoAr) {
            throw new RegistroDeRemocoesIndisponivelException();
        }
        registros.add(new Remocao(TenantContext.exigirAtual(), tipo, registroId,
                instante, solicitadoPor));
    }

    public List<Remocao> registros() {
        return List.copyOf(registros);
    }

    public void ficarForaDoAr() {
        foraDoAr = true;
    }

    public void voltar() {
        foraDoAr = false;
    }

    public void limpar() {
        registros.clear();
        foraDoAr = false;
    }
}
