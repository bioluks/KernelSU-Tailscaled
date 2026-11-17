import { afterEach, describe, expect, it } from 'vitest';
import { parseEngineStatus } from './engine';
import { renderEngineUpdate } from './render';
import { setLanguage } from './i18n';

afterEach(() => setLanguage('en'));

describe('engine updates', () => {
  it('rejects unexpected version content', () => {
    expect(() => parseEngineStatus('{"current":"1.104.2","latest":"1.2.3;id","staged":"","updatePolicy":"engine"}')).toThrow();
  });
  it.each([undefined, null, '', 'automatic', true])('rejects missing or unknown update policy %s', (updatePolicy) => {
    expect(() => parseEngineStatus(JSON.stringify({ current: '1.104.2', latest: '', staged: '', updatePolicy }))).toThrow();
  });
  it.each(['engine', 'module'])('accepts the explicit %s update policy', (updatePolicy) => {
    expect(parseEngineStatus(JSON.stringify({ current: '1.104.2', latest: '', staged: '', updatePolicy })).updatePolicy).toBe(updatePolicy);
  });
  it('only offers applying a verified staged version', () => {
    const root = document.createElement('div');
    renderEngineUpdate(root, parseEngineStatus('{"current":"1.104.2","latest":"1.106.0","staged":"","updatePolicy":"engine"}'));
    expect(root.querySelector('[data-action=engine-prepare]')).not.toBeNull();
    expect(root.querySelector('[data-action=engine-apply]')).toBeNull();
    renderEngineUpdate(root, parseEngineStatus('{"current":"1.104.2","latest":"1.106.0","staged":"1.106.0","updatePolicy":"engine"}'));
    expect(root.querySelector('[data-action=engine-apply]')).not.toBeNull();
  });
  it.each(['en', 'zh-CN'] as const)('explains full-module updates in %s without offering standalone engine actions', (language) => {
    setLanguage(language);
    const root = document.createElement('div');
    renderEngineUpdate(root, parseEngineStatus('{"current":"1.104.2","latest":"1.106.0","staged":"1.106.0","updatePolicy":"module"}'));
    expect(root.textContent).toContain(language === 'en' ? 'update the full module' : '更新整个模块');
    expect(root.textContent).toContain(language === 'en' ? 'compatibility patches' : '兼容性补丁');
    expect(root.textContent).toContain('1.106.0');
    expect(root.textContent).not.toContain(language === 'en' ? 'Verified and ready to install' : '已校验待安装');
    expect(root.querySelector('[data-action=engine-check]')).not.toBeNull();
    expect(root.querySelector('[data-action=engine-prepare]')).toBeNull();
    expect(root.querySelector('[data-action=engine-apply]')).toBeNull();
  });
});
