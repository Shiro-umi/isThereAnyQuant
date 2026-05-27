# 信号处理层 · scipy 数值对照报告

> 证明 `:strategy-server:research` 自实现的频域信号原语与 scipy/numpy 在数值上一致。
> 这是「自实现可信」的前提：编译通过不代表数值正确，唯有逐元素对照黄金值才算验证。

## 对照方法

- **黄金值生成**：`temp/scipy_golden.py`（`uv run --with scipy --with numpy python temp/scipy_golden.py`）
  用 scipy 1.17.1 / numpy 2.4.6 对固定合成信号算出各原语标准结果，导出 `temp/scipy_golden.json`。
- **合成信号**：长度 512，两个正弦（周期 ~4 天 / ~7 天）+ 固定噪声；y 相对 x 整体相位滞后 0.6 rad。
  噪声序列直接从黄金 JSON 读入 Kotlin 侧，**避免跨语言 RNG 差异**，保证两侧输入逐位一致。
- **Kotlin 断言**：`SignalCrossValidationTest`，对同一信号跑自实现，逐元素比对，断言最大误差 ≤ 容差。
- **Python 只用于离线对照**，不进生产管线。

## 对照结果（全部通过）

| 原语 | 对照目标 | 最大误差 | 容差 |
|---|---|---|---|
| Hann 窗（periodic, 30/40/60） | `scipy.signal.windows.hann(sym=False)` | 3.3e-16 | 1e-12 |
| FFT（去均值+加窗+补零到 64） | `numpy.fft.fft` | 5.3e-15 | 1e-9 |
| Welch 相干性（nperseg 64/128） | `scipy.signal.coherence` | 2.5e-15 | 1e-9 |
| Butterworth 带通系数（4 阶, Wn=[0.25,0.40]） | `scipy.signal.butter` | 4.1e-12 | 1e-6 |
| lfilter（因果单向） | `scipy.signal.lfilter` | 3.7e-14 | 1e-9 |
| filtfilt（零相位双向） | `scipy.signal.filtfilt` | 4.7e-14 | 1e-6 |

## 实现要点（与 scipy 对齐的关键细节）

1. **Hann 窗用 periodic（`sym=False`）**：分母 N 而非 N−1。谱分析必须用 periodic，否则系统性偏差。
2. **Welch 相干性靠分段平均**：单段 γ² 恒为 1；跨段平均 ⟨Pxx⟩⟨Pyy⟩⟨Pxy⟩ 后才有意义。窗归一化常数在分子分母对消。
3. **Butterworth 带通**：模拟低通原型极点 → lowpass→bandpass 频率变换（阶数翻倍）→ 双线性变换 →
   中心频率二次归一化。2N+1 个系数，奇数项为 0，对称。
4. **filtfilt 两个易错点**（本次踩坑并修复）：
   - `lfilter_zi` 稳态初值必须**解线性系统 `(I−A)·zi = B`**（LU 分解），不能简单回代——首列 −a 项把状态耦合。
   - `padlen` 默认是 **`3·max(len(a),len(b))`**，不是 `3·(max−1)`。padlen 差 3 会让端点误差停在 ~0.024。

## 复跑方式

```bash
uv run --with scipy --with numpy python temp/scipy_golden.py   # 重新生成黄金值
./gradlew :strategy-server:research:test --tests "*SignalCrossValidationTest"
```
