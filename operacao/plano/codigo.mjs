// Gera o código de ativação de um pedido de plano, depois de o Pix ser conferido no extrato.
//
// Uso, com o mesmo segredo do ambiente publicado:
//   FECHADIA_PLANO_SECRET=... node operacao/plano/codigo.mjs <id do pedido> <plano> <valor>
//
// O id e o plano vêm do texto do pedido que o administrador mandou, e o valor é o que o Pix
// conferido no extrato cobre. O plano pode ir como no texto (Completo) ou como no sistema
// (COMPLETO); o valor, como no texto ("R$ 1.234,56", entre aspas por causa do espaço, ou 123,45)
// ou como no sistema (123.45).
//
// O código é a assinatura HMAC-SHA256 do id do pedido, do plano e do valor; a aplicação a recalcula
// com o plano e o valor gravados no pedido, então um texto alterado dá um código que ela recusa.
// Este script não toca o banco nem a Conta, e o mesmo pedido sempre dá o mesmo código.
//
// Sem dependência: só o Node. O teste ao lado confere o mesmo vetor fixo que a suíte Java confere
// na aplicação.
import { createHmac } from 'node:crypto'
import { pathToFileURL } from 'node:url'

const ALFABETO = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
const PLANOS = ['COMPLETO']
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const MINIMO_DE_BYTES = 32

/** O código do pedido, em quatro grupos de quatro caracteres, como a aplicação o mostra. */
export function codigoDoPedido(segredo, pedidoId, plano, valor) {
  if (!segredo || Buffer.byteLength(segredo, 'utf8') < MINIMO_DE_BYTES) {
    throw new Error(`FECHADIA_PLANO_SECRET precisa de ao menos ${MINIMO_DE_BYTES} bytes`)
  }
  const id = String(pedidoId ?? '').trim().toLowerCase()
  if (!UUID.test(id)) throw new Error(`id de pedido invalido: ${pedidoId}`)
  const doSistema = String(plano ?? '').trim().toUpperCase()
  if (!PLANOS.includes(doSistema)) {
    throw new Error(`plano invalido: ${plano}; use Completo`)
  }
  const valorDoSistema = comoNoSistema(valor)

  const assinatura = createHmac('sha256', Buffer.from(segredo, 'utf8'))
    .update(`pedido-de-plano|${id}|${doSistema}|${valorDoSistema}`, 'utf8')
    .digest()
  return base32(assinatura.subarray(0, 10)).match(/.{4}/g).join('-')
}

// O valor como a aplicação o guarda e assina: ponto decimal, duas casas, sem separador de milhar
// nem zero à esquerda. Com vírgula, ele veio como no texto do pedido, em que o ponto separa o
// milhar; sem vírgula, o ponto é o decimal. O que não cabe em nenhum dos dois, como 1.234 sem
// vírgula, é recusado em vez de adivinhado.
function comoNoSistema(valor) {
  // \s também tira o espaço não separável que a tela põe depois do R$.
  let texto = String(valor ?? '').replace(/R\$/i, '').replace(/\s+/g, '')
  if (texto.includes(',')) texto = texto.replace(/\./g, '').replace(',', '.')
  const partes = /^(\d+)(?:\.(\d{1,2}))?$/.exec(texto)
  if (!partes) throw new Error(`valor invalido: ${valor}; use o valor do pedido, como 123,45`)
  const reais = partes[1].replace(/^0+(?=\d)/, '')
  const centavos = (partes[2] ?? '').padEnd(2, '0')
  return `${reais}.${centavos}`
}

// Cada 5 bits, do mais significativo ao menos, viram um caractere do alfabeto de Crockford, que
// não tem I, L, O nem U, as letras que se confundem com algarismos ao ditar ou digitar.
function base32(bytes) {
  let acumulado = 0
  let bits = 0
  let texto = ''
  for (const byte of bytes) {
    acumulado = (acumulado << 8) | byte
    bits += 8
    while (bits >= 5) {
      bits -= 5
      texto += ALFABETO[(acumulado >>> bits) & 0x1f]
    }
    acumulado &= (1 << bits) - 1
  }
  return texto
}

// Roda como comando só quando chamado direto; o teste importa a função sem executar nada.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [pedidoId, plano, valor] = process.argv.slice(2)
  try {
    console.log(codigoDoPedido(process.env.FECHADIA_PLANO_SECRET, pedidoId, plano, valor))
  } catch (erro) {
    console.error(erro.message)
    console.error('Uso: FECHADIA_PLANO_SECRET=... node operacao/plano/codigo.mjs'
      + ' <id do pedido> <plano> <valor>')
    process.exit(1)
  }
}
