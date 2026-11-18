// gen_users.py가 만든 CSV를 읽는다 (외부 라이브러리 없이 따옴표를 처리하는 파서)
import { SharedArray } from 'k6/data';
import { DATA_DIR } from './config.js';

export function parseCsv(text) {
    const src = text.replace(/^﻿/, '');
    const rows = [];
    let row = [];
    let field = '';
    let quoted = false;
    for (let i = 0; i < src.length; i++) {
        const c = src[i];
        if (quoted) {
            if (c === '"') {
                if (src[i + 1] === '"') { field += '"'; i++; } else { quoted = false; }
            } else {
                field += c;
            }
        } else if (c === '"') {
            quoted = true;
        } else if (c === ',') {
            row.push(field); field = '';
        } else if (c === '\n' || c === '\r') {
            if (c === '\r' && src[i + 1] === '\n') i++;
            row.push(field); field = '';
            if (row.length > 1 || row[0] !== '') rows.push(row);
            row = [];
        } else {
            field += c;
        }
    }
    if (field !== '' || row.length) { row.push(field); rows.push(row); }
    const [header, ...body] = rows;
    return body.map((r) => {
        const o = {};
        header.forEach((h, i) => { o[h] = r[i]; });
        return o;
    });
}

function load(name) {
    return new SharedArray(name, () => parseCsv(open(`${DATA_DIR}/${name}`)));
}

export const sessions = load('referrer_sessions.csv');
export const referrers = load('referrer_users.csv');
export const managers = load('lab_managers.csv');
export const labs = load('labs.csv');
export const equipments = load('equipments.csv');
export const todayExams = load('today_examinations.csv');
export const partnerships = load('partnerships.csv');
