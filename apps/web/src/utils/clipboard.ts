interface ClipboardCell {
  text: string
  background: string
  color: string
  bold: boolean
  align: CanvasTextAlign
}

const MAX_ROWS = 80
const MAX_COLUMNS = 24
const MAX_COLUMN_WIDTH = 280
const MIN_COLUMN_WIDTH = 76
const CELL_PADDING = 12
const ROW_HEIGHT = 32

export function clipboardContainsTable(html: string, text: string): boolean {
  if (html) {
    const document = new DOMParser().parseFromString(html, 'text/html')
    if (document.querySelector('table')) return true
  }
  const lines = text.split(/\r?\n/).filter((line) => line.trim())
  return lines.length >= 2 && lines.some((line) => line.includes('\t'))
}

export async function clipboardTableToImage(html: string, text: string): Promise<File | null> {
  const rows = html ? readHtmlTable(html) : readTextTable(text)
  if (rows.length < 2) return null

  const columnCount = Math.min(MAX_COLUMNS, Math.max(...rows.map((row) => row.length)))
  const normalizedRows = rows.slice(0, MAX_ROWS).map((row) => Array.from({ length: columnCount }, (_, index) => row[index] || emptyCell()))
  const canvas = document.createElement('canvas')
  const measure = canvas.getContext('2d')
  if (!measure) return null
  measure.font = '13px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'

  const widths = Array.from({ length: columnCount }, (_, column) => {
    const maxTextWidth = Math.max(...normalizedRows.map((row) => measure.measureText(row[column].text).width))
    return Math.min(MAX_COLUMN_WIDTH, Math.max(MIN_COLUMN_WIDTH, Math.ceil(maxTextWidth + CELL_PADDING * 2)))
  })
  const width = widths.reduce((sum, value) => sum + value, 0) + 1
  const height = normalizedRows.length * ROW_HEIGHT + 1
  const scale = window.devicePixelRatio > 1 ? 2 : 1
  canvas.width = width * scale
  canvas.height = height * scale
  canvas.style.width = `${width}px`
  canvas.style.height = `${height}px`
  const context = canvas.getContext('2d')
  if (!context) return null
  context.scale(scale, scale)
  context.fillStyle = '#ffffff'
  context.fillRect(0, 0, width, height)
  context.font = '13px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
  context.textBaseline = 'middle'

  normalizedRows.forEach((row, rowIndex) => {
    let left = 0
    row.forEach((cell, columnIndex) => {
      const cellWidth = widths[columnIndex]
      context.fillStyle = cell.background || (rowIndex === 0 ? '#eef5ff' : '#ffffff')
      context.fillRect(left, rowIndex * ROW_HEIGHT, cellWidth, ROW_HEIGHT)
      context.strokeStyle = '#d7e0ea'
      context.lineWidth = 1
      context.strokeRect(left + 0.5, rowIndex * ROW_HEIGHT + 0.5, cellWidth, ROW_HEIGHT)
      context.fillStyle = cell.color || '#26384b'
      context.font = `${cell.bold || rowIndex === 0 ? '600' : '400'} 13px -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`
      const textLeft = cell.align === 'right' ? left + cellWidth - CELL_PADDING : left + CELL_PADDING
      context.textAlign = cell.align
      context.fillText(trimCellText(context, cell.text, cellWidth - CELL_PADDING * 2), textLeft, rowIndex * ROW_HEIGHT + ROW_HEIGHT / 2)
      left += cellWidth
    })
  })

  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
  return blob ? new File([blob], 'excel-table.png', { type: 'image/png' }) : null
}

function emptyCell(): ClipboardCell {
  return { text: '', background: '', color: '', bold: false, align: 'left' }
}

function readHtmlTable(html: string): ClipboardCell[][] {
  const document = new DOMParser().parseFromString(html, 'text/html')
  const table = document.querySelector('table')
  if (!table) return []
  return Array.from(table.rows).map((row) => Array.from(row.cells).map((cell) => {
    const style = cell.getAttribute('style') || ''
    return {
      text: (cell.textContent || '').replace(/\s+/g, ' ').trim(),
      background: readStyle(style, 'background-color'),
      color: readStyle(style, 'color'),
      bold: /font-weight\s*:\s*(bold|[6-9]00)/i.test(style),
      align: readTextAlign(style),
    }
  }))
}

function readTextTable(text: string): ClipboardCell[][] {
  return text.split(/\r?\n/).filter((line) => line.trim()).map((line) => line.split('\t').map((value) => ({
    ...emptyCell(),
    text: value.trim(),
  })))
}

function readStyle(style: string, property: string): string {
  return style.match(new RegExp(`${property}\\s*:\\s*([^;]+)`, 'i'))?.[1]?.trim() || ''
}

function readTextAlign(style: string): CanvasTextAlign {
  const align = readStyle(style, 'text-align').toLowerCase()
  return align === 'center' ? 'center' : align === 'right' ? 'right' : 'left'
}

function trimCellText(context: CanvasRenderingContext2D, value: string, maxWidth: number): string {
  if (context.measureText(value).width <= maxWidth) return value
  let result = value
  while (result && context.measureText(`${result}…`).width > maxWidth) result = result.slice(0, -1)
  return `${result}…`
}
