/**
 * Minimal QR Code Generator.
 * Supports byte mode encoding, error correction level M, versions 1-10.
 * No external dependencies.
 *
 * Public API:
 *   generateQR(text) -> boolean[][] (QR matrix)
 *   toSvg(matrix, moduleSize) -> SVG string
 */

// --- EC Level M parameters per version ---
// [totalCodewords, dataCodewords, ecPerBlock, [[numBlocks, dataPerBlock], ...]]
const VERSIONS = [
    null,
    [26,  16,  10, [[1, 16]]],
    [44,  28,  16, [[1, 28]]],
    [70,  44,  26, [[1, 44]]],
    [100, 64,  18, [[2, 32]]],
    [134, 86,  24, [[2, 43]]],
    [172, 108, 16, [[4, 27]]],
    [196, 124, 18, [[4, 31]]],
    [242, 154, 22, [[2, 38], [2, 39]]],
    [292, 182, 22, [[3, 36], [2, 37]]],
    [346, 216, 26, [[4, 43], [1, 44]]],
];

// Alignment pattern center coordinates per version
const ALIGN_POS = [
    null, [], [6,18], [6,22], [6,26], [6,30],
    [6,34], [6,22,38], [6,24,42], [6,26,46], [6,28,50],
];

// --- GF(256) arithmetic (primitive polynomial 0x11d) ---

const GF_EXP = new Uint8Array(512);
const GF_LOG = new Uint8Array(256);

{
    let x = 1;
    for (let i = 0; i < 255; i++) {
        GF_EXP[i] = x;
        GF_LOG[x] = i;
        x <<= 1;
        if (x >= 256) x ^= 285;
    }
    for (let i = 255; i < 512; i++) {
        GF_EXP[i] = GF_EXP[i - 255];
    }
}

function gfMul(a, b) {
    if (a === 0 || b === 0) return 0;
    return GF_EXP[GF_LOG[a] + GF_LOG[b]];
}

// --- Reed-Solomon ---

function rsGeneratorPoly(n) {
    let poly = [1];
    for (let i = 0; i < n; i++) {
        const np = new Array(poly.length + 1).fill(0);
        for (let j = 0; j < poly.length; j++) {
            np[j] ^= poly[j];
            np[j + 1] ^= gfMul(poly[j], GF_EXP[i]);
        }
        poly = np;
    }
    return poly;
}

function rsEncode(data, ecCount) {
    const gen = rsGeneratorPoly(ecCount);
    const buf = new Array(data.length + ecCount).fill(0);
    for (let i = 0; i < data.length; i++) buf[i] = data[i];

    for (let i = 0; i < data.length; i++) {
        const coeff = buf[i];
        if (coeff !== 0) {
            for (let j = 0; j < gen.length; j++) {
                buf[i + j] ^= gfMul(gen[j], coeff);
            }
        }
    }
    return buf.slice(data.length);
}

// --- Data encoding (byte mode) ---

function encodeData(text, version) {
    const totalDataCW = VERSIONS[version][1];
    const bytes = new TextEncoder().encode(text);

    const bits = [];
    const push = (val, cnt) => {
        for (let i = cnt - 1; i >= 0; i--) bits.push((val >> i) & 1);
    };

    // Mode indicator: byte = 0100
    push(0b0100, 4);
    // Character count
    push(bytes.length, version <= 9 ? 8 : 16);
    // Data bytes
    for (const b of bytes) push(b, 8);

    // Terminator
    const maxBits = totalDataCW * 8;
    const termLen = Math.min(4, maxBits - bits.length);
    push(0, termLen);

    // Byte-align
    while (bits.length % 8 !== 0) bits.push(0);

    // Convert to bytes
    const out = [];
    for (let i = 0; i < bits.length; i += 8) {
        let byte = 0;
        for (let j = 0; j < 8; j++) byte = (byte << 1) | bits[i + j];
        out.push(byte);
    }

    // Pad bytes
    let pad = 0;
    while (out.length < totalDataCW) {
        out.push(pad % 2 === 0 ? 0xEC : 0x11);
        pad++;
    }
    return out;
}

// --- Interleaving ---

function interleave(dataBytes, version) {
    const info = VERSIONS[version];
    const ecPerBlock = info[2];
    const groups = info[3];

    const blocks = [];
    let off = 0;
    for (const [numBlk, dataPerBlk] of groups) {
        for (let i = 0; i < numBlk; i++) {
            blocks.push(dataBytes.slice(off, off + dataPerBlk));
            off += dataPerBlk;
        }
    }

    const ecBlocks = blocks.map(b => rsEncode(b, ecPerBlock));

    const result = [];
    const maxLen = Math.max(...blocks.map(b => b.length));
    for (let i = 0; i < maxLen; i++) {
        for (const blk of blocks) {
            if (i < blk.length) result.push(blk[i]);
        }
    }
    for (let i = 0; i < ecPerBlock; i++) {
        for (const ec of ecBlocks) result.push(ec[i]);
    }
    return result;
}

// --- Matrix construction ---

function makeMatrix(version) {
    const size = version * 4 + 17;
    const matrix = Array.from({ length: size }, () => new Array(size).fill(null));
    const reserved = Array.from({ length: size }, () => new Array(size).fill(false));
    return { matrix, reserved, size };
}

function setModule(m, r, c, dark) {
    if (r >= 0 && r < m.size && c >= 0 && c < m.size) {
        m.matrix[r][c] = dark;
        m.reserved[r][c] = true;
    }
}

function placeFinderPattern(m, row, col) {
    for (let r = -1; r <= 7; r++) {
        for (let c = -1; c <= 7; c++) {
            const rr = row + r, cc = col + c;
            if (rr < 0 || rr >= m.size || cc < 0 || cc >= m.size) continue;
            let dark = false;
            if (r >= 0 && r <= 6 && c >= 0 && c <= 6) {
                dark = r === 0 || r === 6 || c === 0 || c === 6 ||
                       (r >= 2 && r <= 4 && c >= 2 && c <= 4);
            }
            m.matrix[rr][cc] = dark;
            m.reserved[rr][cc] = true;
        }
    }
}

function placeAlignmentPattern(m, row, col) {
    for (let r = -2; r <= 2; r++) {
        for (let c = -2; c <= 2; c++) {
            const dark = Math.abs(r) === 2 || Math.abs(c) === 2 || (r === 0 && c === 0);
            m.matrix[row + r][col + c] = dark;
            m.reserved[row + r][col + c] = true;
        }
    }
}

function placeFunctionPatterns(m, version) {
    const { size } = m;

    // Finder patterns (3 corners)
    placeFinderPattern(m, 0, 0);
    placeFinderPattern(m, 0, size - 7);
    placeFinderPattern(m, size - 7, 0);

    // Alignment patterns
    const positions = ALIGN_POS[version];
    for (const row of positions) {
        for (const col of positions) {
            if (m.reserved[row][col]) continue;
            placeAlignmentPattern(m, row, col);
        }
    }

    // Timing patterns
    for (let i = 8; i < size - 8; i++) {
        const dark = i % 2 === 0;
        if (!m.reserved[6][i]) { m.matrix[6][i] = dark; m.reserved[6][i] = true; }
        if (!m.reserved[i][6]) { m.matrix[i][6] = dark; m.reserved[i][6] = true; }
    }

    // Dark module
    m.matrix[size - 8][8] = true;
    m.reserved[size - 8][8] = true;

    // Reserve format info areas
    for (let i = 0; i <= 8; i++) {
        m.reserved[8][i] = true;
        m.reserved[i][8] = true;
    }
    for (let i = 0; i <= 7; i++) {
        m.reserved[8][size - 1 - i] = true;
        m.reserved[size - 1 - i][8] = true;
    }

    // Reserve version info areas (version >= 7)
    if (version >= 7) {
        for (let r = 0; r < 6; r++) {
            for (let c = size - 11; c < size - 8; c++) {
                m.reserved[r][c] = true;
                m.reserved[c][r] = true;
            }
        }
    }
}

function placeData(m, codewords) {
    const { size, reserved, matrix } = m;
    const bits = [];
    for (const cw of codewords) {
        for (let i = 7; i >= 0; i--) bits.push((cw >> i) & 1);
    }

    let bitIdx = 0;
    let upward = true;

    for (let right = size - 1; right >= 1; right -= 2) {
        if (right === 6) right = 5; // skip timing column

        const rows = upward
            ? Array.from({ length: size }, (_, i) => size - 1 - i)
            : Array.from({ length: size }, (_, i) => i);

        for (const row of rows) {
            for (let dx = 0; dx <= 1; dx++) {
                const col = right - dx;
                if (col < 0 || reserved[row][col]) continue;
                matrix[row][col] = bitIdx < bits.length ? bits[bitIdx] === 1 : false;
                bitIdx++;
            }
        }
        upward = !upward;
    }
}

// --- Masking ---

const MASK_FNS = [
    (r, c) => (r + c) % 2 === 0,
    (r, c) => r % 2 === 0,
    (r, c) => c % 3 === 0,
    (r, c) => (r + c) % 3 === 0,
    (r, c) => (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0,
    (r, c) => (r * c) % 2 + (r * c) % 3 === 0,
    (r, c) => ((r * c) % 2 + (r * c) % 3) % 2 === 0,
    (r, c) => ((r + c) % 2 + (r * c) % 3) % 2 === 0,
];

function applyMask(matrix, reserved, maskIdx) {
    const size = matrix.length;
    const fn = MASK_FNS[maskIdx];
    const masked = matrix.map(row => [...row]);
    for (let r = 0; r < size; r++) {
        for (let c = 0; c < size; c++) {
            if (!reserved[r][c] && fn(r, c)) {
                masked[r][c] = !masked[r][c];
            }
        }
    }
    return masked;
}

// --- Format & version info ---

function computeFormatInfo(mask) {
    // EC level M indicator = 00
    const data = (0b00 << 3) | mask;
    let bits = data << 10;
    // BCH(15,5) generator: x^10 + x^8 + x^5 + x^4 + x^2 + x + 1
    const gen = 0b10100110111;
    for (let i = 14; i >= 10; i--) {
        if (bits & (1 << i)) bits ^= gen << (i - 10);
    }
    return ((data << 10) | bits) ^ 0b101010000010010;
}

function placeFormatInfo(matrix, mask) {
    const size = matrix.length;
    const fmt = computeFormatInfo(mask);

    // Copy 1: around top-left finder
    const posA = [
        [8,0],[8,1],[8,2],[8,3],[8,4],[8,5],[8,7],[8,8],
        [7,8],[5,8],[4,8],[3,8],[2,8],[1,8],[0,8],
    ];
    // Copy 2: near top-right and bottom-left finders
    const posB = [
        [size-1,8],[size-2,8],[size-3,8],[size-4,8],[size-5,8],[size-6,8],[size-7,8],
        [8,size-8],[8,size-7],[8,size-6],[8,size-5],[8,size-4],[8,size-3],[8,size-2],[8,size-1],
    ];

    for (let i = 0; i < 15; i++) {
        const dark = ((fmt >> (14 - i)) & 1) === 1;
        matrix[posA[i][0]][posA[i][1]] = dark;
        matrix[posB[i][0]][posB[i][1]] = dark;
    }
}

function placeVersionInfo(matrix, version) {
    if (version < 7) return;
    const size = matrix.length;

    // BCH(18,6) generator: x^12 + x^11 + x^10 + x^9 + x^8 + x^5 + x^2 + 1
    let data = version << 12;
    const gen = 0b1111100100101;
    for (let i = 17; i >= 12; i--) {
        if (data & (1 << i)) data ^= gen << (i - 12);
    }
    const versionBits = (version << 12) | data;

    for (let i = 0; i < 18; i++) {
        const dark = ((versionBits >> i) & 1) === 1;
        const row = Math.floor(i / 3);
        const col = size - 11 + (i % 3);
        matrix[row][col] = dark;
        matrix[col][row] = dark;
    }
}

// --- Penalty scoring ---

function computePenalty(matrix) {
    const size = matrix.length;
    let penalty = 0;

    // Rule 1: runs of 5+ same color
    for (let r = 0; r < size; r++) {
        let cnt = 1;
        for (let c = 1; c < size; c++) {
            if (matrix[r][c] === matrix[r][c - 1]) { cnt++; }
            else { if (cnt >= 5) penalty += cnt - 2; cnt = 1; }
        }
        if (cnt >= 5) penalty += cnt - 2;
    }
    for (let c = 0; c < size; c++) {
        let cnt = 1;
        for (let r = 1; r < size; r++) {
            if (matrix[r][c] === matrix[r - 1][c]) { cnt++; }
            else { if (cnt >= 5) penalty += cnt - 2; cnt = 1; }
        }
        if (cnt >= 5) penalty += cnt - 2;
    }

    // Rule 2: 2x2 blocks
    for (let r = 0; r < size - 1; r++) {
        for (let c = 0; c < size - 1; c++) {
            const v = matrix[r][c];
            if (v === matrix[r][c+1] && v === matrix[r+1][c] && v === matrix[r+1][c+1]) {
                penalty += 3;
            }
        }
    }

    // Rule 3: finder-like patterns (1:1:3:1:1 with 4-module light on one side)
    const pat1 = [true,false,true,true,true,false,true,false,false,false,false];
    const pat2 = [...pat1].reverse();
    for (let r = 0; r < size; r++) {
        for (let c = 0; c <= size - 11; c++) {
            let match1 = true, match2 = true;
            for (let k = 0; k < 11; k++) {
                if (matrix[r][c+k] !== pat1[k]) match1 = false;
                if (matrix[r][c+k] !== pat2[k]) match2 = false;
            }
            if (match1 || match2) penalty += 40;
        }
    }
    for (let c = 0; c < size; c++) {
        for (let r = 0; r <= size - 11; r++) {
            let match1 = true, match2 = true;
            for (let k = 0; k < 11; k++) {
                if (matrix[r+k][c] !== pat1[k]) match1 = false;
                if (matrix[r+k][c] !== pat2[k]) match2 = false;
            }
            if (match1 || match2) penalty += 40;
        }
    }

    // Rule 4: dark/light balance
    let darkCount = 0;
    for (let r = 0; r < size; r++) {
        for (let c = 0; c < size; c++) {
            if (matrix[r][c]) darkCount++;
        }
    }
    const pct = (darkCount * 100) / (size * size);
    const prev5 = Math.floor(pct / 5) * 5;
    const next5 = prev5 + 5;
    penalty += Math.min(Math.abs(prev5 - 50) / 5, Math.abs(next5 - 50) / 5) * 10;

    return penalty;
}

// --- Public API ---

export function generateQR(text) {
    const bytes = new TextEncoder().encode(text);

    // Find smallest version that fits
    let version = 0;
    for (let v = 1; v <= 10; v++) {
        const overhead = 4 + (v <= 9 ? 8 : 16); // mode + count indicator bits
        const capacity = VERSIONS[v][1] * 8;
        if (overhead + bytes.length * 8 <= capacity) {
            version = v;
            break;
        }
    }
    if (version === 0) throw new Error('Data too long for QR code');

    const dataBytes = encodeData(text, version);
    const codewords = interleave(dataBytes, version);

    const m = makeMatrix(version);
    placeFunctionPatterns(m, version);
    placeData(m, codewords);

    let bestMask = 0;
    let bestPenalty = Infinity;
    let bestMatrix = null;

    for (let mask = 0; mask < 8; mask++) {
        const masked = applyMask(m.matrix, m.reserved, mask);
        placeFormatInfo(masked, mask);
        placeVersionInfo(masked, version);
        const p = computePenalty(masked);
        if (p < bestPenalty) {
            bestPenalty = p;
            bestMask = mask;
            bestMatrix = masked;
        }
    }

    return bestMatrix;
}

export function toSvg(matrix, moduleSize = 6) {
    const size = matrix.length;
    const quiet = 4;
    const total = (size + quiet * 2) * moduleSize;

    const rects = [];
    for (let r = 0; r < size; r++) {
        for (let c = 0; c < size; c++) {
            if (matrix[r][c]) {
                const x = (c + quiet) * moduleSize;
                const y = (r + quiet) * moduleSize;
                rects.push(`<rect x="${x}" y="${y}" width="${moduleSize}" height="${moduleSize}"/>`);
            }
        }
    }

    return [
        `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${total} ${total}" width="${total}" height="${total}">`,
        `<rect width="${total}" height="${total}" fill="#fff"/>`,
        `<g fill="#000">${rects.join('')}</g>`,
        `</svg>`,
    ].join('');
}
