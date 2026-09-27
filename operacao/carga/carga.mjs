// Carga de correção contra uma instalação do Caixa Simples.
//
// Em cada Conta, todas em paralelo: abre o caixa, faz 100 Vendas com rede (dinheiro com troco,
// cartão e fiado, em rodízio), uma sangria e um lote de 100 gestos registrados sem rede (25 Vendas
// de 4 gestos cada), enviado duas vezes. Depois confere a lista de Vendas do caixa e o relatório do
// dia contra o que foi feito aqui, e fecha o caixa com o valor esperado calculado aqui.
//
// Passa com: nenhuma resposta 5xx; nenhuma Venda perdida nem duplicada, inclusive no reenvio do
// lote; a lista, o relatório do dia e o fechamento batendo com os totais daqui. A latência (p50 e
// p95 por rota) sai como linha de base, sem meta.
//
// Uso, com Node 20 ou mais novo e sem dependência:
//
//   CARGA_URL=http://localhost:18080 \
//   CARGA_EMAILS=a@exemplo.com,b@exemplo.com,c@exemplo.com \
//   CARGA_SENHA=... node operacao/carga/carga.mjs
//
// Cada e-mail é o administrador de uma Conta, todos com a mesma senha. O caixa dele não pode estar
// aberto: o fechamento só confere se o caixa tiver apenas as Vendas da carga.

import { randomUUID } from 'node:crypto';

const URL_BASE = obrigatoria('CARGA_URL').replace(/\/+$/, '');
const EMAILS = obrigatoria('CARGA_EMAILS').split(',').map((email) => email.trim()).filter(Boolean);
const SENHA = obrigatoria('CARGA_SENHA');

const VENDAS_COM_REDE = 100;
// Quatro gestos por Venda (iniciar, item, pagamento e conclusão): 25 Vendas enchem o lote de 100.
const VENDAS_SEM_REDE = 25;
const FORMAS_COM_REDE = ['DINHEIRO', 'CARTAO', 'FIADO'];
// O fiado exige Cliente vinculado, um quinto gesto; sem rede, o rodízio fica em dinheiro e cartão.
const FORMAS_SEM_REDE = ['DINHEIRO', 'CARTAO'];

// Valores em centavos, inteiros, para que nenhuma soma dependa de arredondamento de ponto flutuante.
const ABERTURA = 10000;
const SANGRIA = 5000;
const PRODUTOS = [
  { nome: 'Café da carga', preco: 650 },
  { nome: 'Pão de queijo da carga', preco: 475 },
  { nome: 'Bolo da carga', preco: 800 },
];
const CLIENTE = 'Cliente da carga';
// Com estoque controlado, o saldo é reposto antes da carga: a Venda sem rede que deixa um saldo
// negativo volta com revisão, e a carga confere que o lote foi aplicado sem nenhuma.
const ESTOQUE_PARA_A_CARGA = 1000;

// O dia do relatório é o do balcão, no fuso de referência do sistema.
const FUSO = 'America/Bahia';

const tempos = new Map();
let respostas5xx = 0;

function obrigatoria(nome) {
  const valor = process.env[nome];
  if (!valor) {
    console.error(`Informe ${nome}. O uso está no começo de operacao/carga/carga.mjs.`);
    process.exit(2);
  }
  return valor;
}

const emReais = (centavos) => centavos / 100;
const emCentavos = (valor) => Math.round(Number(valor) * 100);
const somar = (valores) => valores.reduce((total, valor) => total + valor, 0);
const formatar = (centavos) => (centavos / 100).toFixed(2);

function diaNoFuso(instante = new Date()) {
  // en-CA escreve a data como AAAA-MM-DD, o formato que o relatório recebe.
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: FUSO, year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(instante);
}

/**
 * Uma chamada à API, com o tempo anotado sob o nome da rota. Status fora dos esperados interrompe a
 * Conta: a carga mede correção, e uma resposta errada já é o resultado.
 */
async function chamar(conta, metodo, rota, caminho, corpo, esperados) {
  const cabecalhos = {};
  if (conta.token) cabecalhos.Authorization = `Bearer ${conta.token}`;
  if (corpo !== undefined) cabecalhos['Content-Type'] = 'application/json';

  const inicio = performance.now();
  let resposta;
  let texto;
  try {
    resposta = await fetch(URL_BASE + caminho, {
      method: metodo,
      headers: cabecalhos,
      body: corpo === undefined ? undefined : JSON.stringify(corpo),
      signal: AbortSignal.timeout(30_000),
    });
    texto = await resposta.text();
  } catch (erro) {
    throw new Error(`${metodo} ${caminho}: sem resposta (${erro.message})`);
  }
  anotar(`${metodo} ${rota}`, performance.now() - inicio);

  if (resposta.status >= 500) respostas5xx++;
  if (!esperados.includes(resposta.status)) {
    throw new Error(`${metodo} ${caminho}: esperado ${esperados.join(' ou ')}, `
      + `veio ${resposta.status} ${texto.slice(0, 300)}`);
  }
  return texto ? JSON.parse(texto) : null;
}

function anotar(rota, ms) {
  if (!tempos.has(rota)) tempos.set(rota, []);
  tempos.get(rota).push(ms);
}

async function entrar(email) {
  const conta = { email, token: null };
  const { token } = await chamar(conta, 'POST', '/api/auth/login', '/api/auth/login',
    { email, senha: SENHA }, [200]);
  conta.token = token;
  conta.identidade = await chamar(conta, 'GET', '/api/auth/eu', '/api/auth/eu', undefined, [200]);
  if (conta.identidade.perfil !== 'ADMIN') {
    throw new Error(`${email} não é administrador: o fiado e o relatório pedem esse perfil`);
  }
  return conta;
}

async function garantirProdutos(conta) {
  let lista = await chamar(conta, 'GET', '/api/produtos', '/api/produtos', undefined, [200]);
  for (const modelo of PRODUTOS) {
    if (!lista.some((produto) => produto.nome === modelo.nome)) {
      await chamar(conta, 'POST', '/api/produtos', '/api/produtos',
        { tipo: 'PRODUTO', nome: modelo.nome, preco: emReais(modelo.preco) }, [201]);
    }
  }
  lista = await chamar(conta, 'GET', '/api/produtos', '/api/produtos', undefined, [200]);

  // O preço que vale é o do cadastro, que pode ter sido editado depois de uma carga anterior.
  const produtos = PRODUTOS.map((modelo) => {
    const produto = lista.find((item) => item.nome === modelo.nome);
    return { id: produto.id, nome: produto.nome, preco: emCentavos(produto.preco), versao: produto.versao };
  });

  if (conta.identidade.estoqueHabilitado) {
    const saldos = await chamar(conta, 'GET', '/api/estoque/produtos', '/api/estoque/produtos',
      undefined, [200]);
    for (const produto of produtos) {
      const atual = Number(saldos.find((saldo) => saldo.id === produto.id)?.estoqueAtual ?? 0);
      if (atual < ESTOQUE_PARA_A_CARGA) {
        await chamar(conta, 'POST', '/api/estoque/produtos/{id}/ajustes',
          `/api/estoque/produtos/${produto.id}/ajustes`,
          { diferenca: ESTOQUE_PARA_A_CARGA - atual, motivo: 'Estoque para a carga' }, [204]);
      }
    }
  }
  return produtos;
}

async function garantirCliente(conta) {
  const lista = await chamar(conta, 'GET', '/api/clientes', '/api/clientes', undefined, [200]);
  const existente = lista.find((cliente) => cliente.nome === CLIENTE);
  if (existente) return existente.id;
  const { id } = await chamar(conta, 'POST', '/api/clientes', '/api/clientes', { nome: CLIENTE }, [201]);
  return id;
}

// O cliente paga com a próxima nota de 10 acima do total, então toda Venda em dinheiro tem troco.
const recebidoComTroco = (total) => Math.ceil((total + 1) / 1000) * 1000;

async function vendaComRede(conta, sessaoId, produtos, clienteId, indice) {
  const forma = FORMAS_COM_REDE[indice % FORMAS_COM_REDE.length];
  const { id } = await chamar(conta, 'POST', '/api/vendas', '/api/vendas',
    { sessaoCaixaId: sessaoId }, [201]);

  let total = 0;
  const quantidadeDeItens = 1 + (indice % 2);
  for (let posicao = 0; posicao < quantidadeDeItens; posicao++) {
    const produto = produtos[(indice + posicao) % produtos.length];
    const quantidade = 1 + ((indice + posicao) % 3);
    await chamar(conta, 'POST', '/api/vendas/{id}/itens', `/api/vendas/${id}/itens`,
      { produtoId: produto.id, quantidade, desconto: 0 }, [201]);
    total += produto.preco * quantidade;
  }

  if (forma === 'FIADO') {
    await chamar(conta, 'PUT', '/api/vendas/{id}/cliente', `/api/vendas/${id}/cliente`,
      { clienteId }, [204]);
  }
  const pagamento = { forma, valor: emReais(total) };
  if (forma === 'DINHEIRO') pagamento.valorRecebido = emReais(recebidoComTroco(total));
  const { troco } = await chamar(conta, 'POST', '/api/vendas/{id}/pagamentos',
    `/api/vendas/${id}/pagamentos`, pagamento, [200]);
  const trocoEsperado = forma === 'DINHEIRO' ? recebidoComTroco(total) - total : 0;
  if (emCentavos(troco) !== trocoEsperado) {
    throw new Error(`Venda ${id}: troco ${troco}, esperado ${formatar(trocoEsperado)}`);
  }

  await chamar(conta, 'POST', '/api/vendas/{id}/conclusao', `/api/vendas/${id}/conclusao`,
    undefined, [204]);
  return { id, total, forma };
}

/**
 * O lote como o aplicativo o monta: cada gesto com o id que o dispositivo gerou e dependendo do
 * gesto anterior da mesma Venda. Os instantes são os de agora, dez milissegundos entre um gesto e o
 * seguinte, porque o servidor manda para revisão o gesto de um relógio muito adiantado.
 */
function montarLote(sessaoCaixaId, produtos) {
  const operacoes = [];
  const vendas = [];
  const inicio = Date.now();
  let gestos = 0;
  const proximoInstante = () => new Date(inicio + 10 * gestos++).toISOString();

  for (let indice = 0; indice < VENDAS_SEM_REDE; indice++) {
    const vendaId = randomUUID();
    const produto = produtos[indice % produtos.length];
    const forma = FORMAS_SEM_REDE[indice % FORMAS_SEM_REDE.length];
    const total = produto.preco;
    let anterior = null;

    const gesto = (tipo, payload, criadoEm) => {
      const operacao = {
        operacaoId: randomUUID(), registroId: vendaId, tipo, payload, versaoBase: null,
        dependeDe: anterior ? [anterior] : [], criadoEm,
      };
      anterior = operacao.operacaoId;
      operacoes.push(operacao);
    };

    const iniciadaEm = proximoInstante();
    gesto('venda.iniciar', { sessaoCaixaId, criadoEm: iniciadaEm }, iniciadaEm);
    gesto('venda.adicionarItem', {
      itemId: randomUUID(), produtoId: produto.id, nome: produto.nome, quantidade: 1,
      precoUnitario: emReais(produto.preco), desconto: 0, versaoProduto: produto.versao,
    }, proximoInstante());
    gesto('venda.registrarPagamento', {
      pagamentoId: randomUUID(), forma, valor: emReais(total),
      valorRecebido: forma === 'DINHEIRO' ? emReais(recebidoComTroco(total)) : null,
    }, proximoInstante());
    const concluidaEm = proximoInstante();
    gesto('venda.concluir', { concluidoEm: concluidaEm }, concluidaEm);

    vendas.push({ id: vendaId, total, forma });
  }
  return { operacoes, vendas };
}

function conferirPrimeiroEnvio(resposta, operacoes) {
  const resultados = resposta.resultados;
  if (resultados.length !== operacoes.length) {
    throw new Error(`o lote teve ${operacoes.length} gestos e ${resultados.length} resultados`);
  }
  resultados.forEach((resultado, posicao) => {
    const operacao = operacoes[posicao];
    if (resultado.operacaoId !== operacao.operacaoId || resultado.resultado !== 'APLICADA') {
      throw new Error(`gesto ${operacao.tipo} ${operacao.operacaoId}: ${resultado.resultado}`
        + ` (${resultado.detalhe ?? 'sem detalhe'})`);
    }
  });
}

function conferirReenvio(primeiro, segundo) {
  const antes = JSON.stringify(primeiro.resultados);
  const depois = JSON.stringify(segundo.resultados);
  if (antes !== depois) {
    throw new Error(`o reenvio do lote respondeu diferente do primeiro envio: ${depois.slice(0, 300)}`);
  }
}

function conferirLista(lista, vendas) {
  const porId = new Map(lista.map((venda) => [venda.id, venda]));
  if (porId.size !== lista.length) {
    throw new Error(`a lista do caixa repete Vendas: ${lista.length} linhas, ${porId.size} ids`);
  }
  for (const venda of vendas) {
    const noServidor = porId.get(venda.id);
    if (!noServidor) throw new Error(`a Venda ${venda.id} (${venda.forma}) não está no caixa`);
    if (noServidor.status !== 'CONCLUIDA' || emCentavos(noServidor.total) !== venda.total) {
      throw new Error(`a Venda ${venda.id} está ${noServidor.status} com ${noServidor.total},`
        + ` esperada CONCLUIDA com ${formatar(venda.total)}`);
    }
  }
  if (lista.length !== vendas.length) {
    throw new Error(`o caixa tem ${lista.length} Vendas, e a carga fez ${vendas.length}`);
  }
}

function conferirRelatorio(antes, depois, vendas) {
  const quantidade = depois.quantidadeDeVendas - antes.quantidadeDeVendas;
  const total = emCentavos(depois.total) - emCentavos(antes.total);
  const esperado = somar(vendas.map((venda) => venda.total));
  if (quantidade !== vendas.length || total !== esperado) {
    throw new Error(`o relatório do dia cresceu ${quantidade} Vendas e ${formatar(total)},`
      + ` esperado ${vendas.length} Vendas e ${formatar(esperado)}`);
  }
}

async function executar(email) {
  const conta = await entrar(email);
  const produtos = await garantirProdutos(conta);
  const clienteId = await garantirCliente(conta);

  const aberta = await chamar(conta, 'GET', '/api/caixa/sessoes/aberta', '/api/caixa/sessoes/aberta',
    undefined, [200, 204]);
  if (aberta) throw new Error(`o caixa de ${email} já está aberto; feche-o antes da carga`);

  // O relatório soma o dia inteiro da Conta; o que se confere é o quanto ele cresceu.
  const dia = diaNoFuso();
  const caminhoDoRelatorio = `/api/relatorios/faturamento/dia?dia=${dia}`;
  const relatorioAntes = await chamar(conta, 'GET', '/api/relatorios/faturamento/dia',
    caminhoDoRelatorio, undefined, [200]);

  const { id: sessaoId } = await chamar(conta, 'POST', '/api/caixa/sessoes', '/api/caixa/sessoes',
    { valorAbertura: emReais(ABERTURA) }, [201]);

  const vendas = [];
  for (let indice = 0; indice < VENDAS_COM_REDE; indice++) {
    vendas.push(await vendaComRede(conta, sessaoId, produtos, clienteId, indice));
  }

  await chamar(conta, 'POST', '/api/caixa/sessoes/{id}/sangrias',
    `/api/caixa/sessoes/${sessaoId}/sangrias`, { valor: emReais(SANGRIA), motivo: 'Sangria da carga' },
    [204]);

  const lote = montarLote(sessaoId, produtos);
  const primeiroEnvio = await chamar(conta, 'POST', '/api/sincronizacao', '/api/sincronizacao',
    { operacoes: lote.operacoes }, [200]);
  conferirPrimeiroEnvio(primeiroEnvio, lote.operacoes);
  const reenvio = await chamar(conta, 'POST', '/api/sincronizacao', '/api/sincronizacao',
    { operacoes: lote.operacoes }, [200]);
  conferirReenvio(primeiroEnvio, reenvio);
  vendas.push(...lote.vendas);

  const lista = await chamar(conta, 'GET', '/api/vendas', `/api/vendas?sessaoCaixaId=${sessaoId}`,
    undefined, [200]);
  conferirLista(lista, vendas);

  const relatorioDepois = await chamar(conta, 'GET', '/api/relatorios/faturamento/dia',
    caminhoDoRelatorio, undefined, [200]);
  if (diaNoFuso() !== dia) {
    throw new Error(`a carga atravessou a meia-noite em ${FUSO}, e o relatório de um dia não a`
      + ' cobre inteira; rode de novo');
  }
  conferirRelatorio(relatorioAntes, relatorioDepois, vendas);

  // Na gaveta: a abertura, mais o dinheiro das Vendas, menos a sangria. Cartão e fiado não entram.
  const dinheiro = somar(vendas.filter((venda) => venda.forma === 'DINHEIRO').map((venda) => venda.total));
  const esperadoNaGaveta = ABERTURA + dinheiro - SANGRIA;
  const sessao = await chamar(conta, 'GET', '/api/caixa/sessoes/{id}', `/api/caixa/sessoes/${sessaoId}`,
    undefined, [200]);
  if (emCentavos(sessao.valorFechamentoEsperado) !== esperadoNaGaveta) {
    throw new Error(`o caixa espera ${sessao.valorFechamentoEsperado}, e a carga calculou`
      + ` ${formatar(esperadoNaGaveta)}`);
  }
  const { diferenca } = await chamar(conta, 'POST', '/api/caixa/sessoes/{id}/fechamento',
    `/api/caixa/sessoes/${sessaoId}/fechamento`, { valorContado: emReais(esperadoNaGaveta) }, [200]);
  if (emCentavos(diferenca) !== 0) {
    throw new Error(`o fechamento com o valor esperado deu diferença de ${diferenca}`);
  }

  return {
    vendas: vendas.length,
    total: somar(vendas.map((venda) => venda.total)),
    gaveta: esperadoNaGaveta,
  };
}

function percentil(ordenados, p) {
  return ordenados[Math.max(0, Math.ceil((p / 100) * ordenados.length) - 1)];
}

function linhaDeTempo(rota, valores) {
  const ordenados = [...valores].sort((a, b) => a - b);
  return `${rota.padEnd(46)} ${String(ordenados.length).padStart(6)}`
    + ` ${percentil(ordenados, 50).toFixed(1).padStart(9)} ${percentil(ordenados, 95).toFixed(1).padStart(9)}`;
}

if (EMAILS.length !== 3) {
  console.error(`A carga roda em 3 Contas, e CARGA_EMAILS tem ${EMAILS.length}.`);
  process.exit(2);
}

console.log(`Carga em ${URL_BASE}, ${EMAILS.length} Contas em paralelo.`);
const inicio = performance.now();
const desfechos = await Promise.allSettled(EMAILS.map((email) => executar(email)));
const segundos = (performance.now() - inicio) / 1000;

let falhou = false;
desfechos.forEach((desfecho, posicao) => {
  if (desfecho.status === 'fulfilled') {
    const { vendas, total, gaveta } = desfecho.value;
    console.log(`${EMAILS[posicao]}: ${vendas} Vendas, ${formatar(total)} no relatório,`
      + ` ${formatar(gaveta)} na gaveta, fechamento sem diferença`);
  } else {
    falhou = true;
    console.log(`${EMAILS[posicao]}: FALHOU, ${desfecho.reason.message}`);
  }
});

console.log(`\n${'rota'.padEnd(46)} ${'n'.padStart(6)} ${'p50 ms'.padStart(9)} ${'p95 ms'.padStart(9)}`);
for (const rota of [...tempos.keys()].sort()) console.log(linhaDeTempo(rota, tempos.get(rota)));
console.log(linhaDeTempo('todas', [...tempos.values()].flat()));

console.log(`\nRespostas 5xx: ${respostas5xx}. Duração: ${segundos.toFixed(1)} s.`);
if (respostas5xx > 0) falhou = true;
console.log(falhou ? 'Resultado: FALHOU' : 'Resultado: PASSOU');
process.exit(falhou ? 1 : 0);
