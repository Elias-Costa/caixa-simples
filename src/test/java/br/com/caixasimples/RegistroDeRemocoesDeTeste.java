package br.com.caixasimples;

import br.com.caixasimples.shared.ContaId;
import br.com.caixasimples.shared.RegistroDeRemocoes;
import br.com.caixasimples.shared.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Memória compartilhada pelos testes para provar a gravação fora do banco. */
public class RegistroDeRemocoesDeTeste implements RegistroDeRemocoes {

    public record Remocao(ContaId conta, Tipo tipo, UUID registroId,
            Instant instante, UUID solicitadoPor) {
    }

    private final List<Remocao> registros = new CopyOnWriteArrayList<>();

    @Override
    public void registrar(Tipo tipo, UUID registroId, Instant instante, UUID solicitadoPor) {
        registros.add(new Remocao(TenantContext.exigirAtual(), tipo, registroId,
                instante, solicitadoPor));
    }

    public List<Remocao> registros() {
        return List.copyOf(registros);
    }

    public void limpar() {
        registros.clear();
    }
}
