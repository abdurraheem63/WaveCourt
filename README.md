# Wave Court

**Wave Court** is an interactive, real-time 2D quantum physics simulation and arcade game for Android. Players use potential-energy rackets to rally, manipulate, and collapse a simulated **Bose-Einstein Condensate (BEC)** governed by the time-dependent **Gross-Pitaevskii Equation (GPE)** at a locked 60 FPS.

---

## Features

- **Real-Time Quantum Fluid Dynamics:** Solves the 2D nonlinear Gross-Pitaevskii equation on a 128 &times; 128 spatial grid in real time.
- **Tunable Nonlinear Self-Interaction (<i>g</i>-Slider):** Adjust the effective scattering length in real time:
  - **<i>g</i> = 0:** Linear Schrödinger dispersion.
  - **<i>g</i> &gt; 0 (Repulsive):** Atoms repel each other, causing accelerated self-defocusing.
  - **<i>g</i> &lt; 0 (Attractive):** Atoms attract each other, enabling self-focusing and dramatic **"Bosenova" collapses**.
- **Distinct Initial Quantum States:**
  - **Squeezed Wavepacket:** An anisotropic Gaussian packet (&sigma;<sub><i>x</i></sub> &ne; &sigma;<sub><i>y</i></sub>) with initial momentum exhibiting asymmetric dispersion.
  - **Quantized Vortex:** A topological phase singularity (&psi;(<i>r</i>, &theta;) = <i>f</i>(<i>r</i>)<i>e</i><sup><i>i</i>&#8467;&theta;</sup>) featuring a zero-density core and quantized angular momentum.
- **Game Modes:**
  - **vs. Reactive AI:** Compete against a heuristic defender that tracks peak probability density |&psi;|<sup>2</sup> at a bounded reaction velocity.
  - **No AI (Sandbox Mode):** Full-court freedom to stir vortices, split wavepackets into interference patterns, and trigger manual condensate collapses.

---

## Physics & Numerical Methods

Wave Court evolves the dimensionless 2D Gross-Pitaevskii equation (&hbar; = <i>m</i> = 1):

<p align="center">
  <i>i</i> (&part;&psi;(<i>x</i>, <i>y</i>, <i>t</i>) / &part;<i>t</i>) = [ &minus;&frac12;&nabla;<sup>2</sup> + <i>V</i>(<i>x</i>, <i>y</i>, <i>t</i>) &minus; <i>i</i>&gamma;(<i>x</i>, <i>y</i>) + <i>g</i>|&psi;(<i>x</i>, <i>y</i>, <i>t</i>)|<sup>2</sup> ] &psi;(<i>x</i>, <i>y</i>, <i>t</i>)
</p>

- **Strang Operator Splitting:** Decouples the non-commuting linear kinetic step from the local potential/nonlinear step in a symmetric half-step / full-step / half-step sequence (<i>O</i>(&Delta;<i>t</i><sup>2</sup>) accuracy), avoiding costly iterative nonlinear solvers.
- **Peaceman–Rachford ADI Crank–Nicolson:** Splits the 2D kinetic Laplacian into sequential 1D tridiagonal systems along <i>x</i> and <i>y</i>, solved in <i>O</i>(<i>N</i><sup>2</sup>) time via the **Thomas Algorithm** with precomputed forward-elimination coefficients.
- **Complex Absorbing Potential (CAP):** A 12-cell sponge boundary layer with a smooth cubic ramp (&gamma;(&eta;) = &gamma;<sub>max</sub>&eta;<sup>3</sup>) absorbs outgoing wavefronts without introducing unphysical edge reflections.
- **Norm Conservation:** Explicit renormalization enforces &int; |&psi;|<sup>2</sup> <i>dA</i> = 1 after every timestep.

---

## Technical Architecture

- **Decoupled Multithreading:** The physics solver runs on a dedicated background worker thread, keeping the UI thread completely non-blocking.
- **Cache-Friendly Memory Layout:** The 2D complex wavefunction grid is flattened into separate 1D real and imaginary arrays (Structure of Arrays) to maximize CPU cache locality during ADI sweeps.
- **Lock-Free Double Buffering:** Uses an atomic reference ping-pong buffer and frame counter to synchronize physics updates with the UI compositor without frame tearing or lock contention.
- **AGSL GPU Rendering:** Offloads probability-to-color mapping (Black &rarr; Yellow &rarr; Orange &rarr; White) with power-law low-density boosting and 4-tap quintic smootherstep interpolation to the GPU via **Android Graphics Shader Language (AGSL)**.

---

## Getting Started

### Prerequisites
- Android Studio (Ladybug or newer recommended)
- Android SDK with **API 33+** (required for AGSL `RuntimeShader` support)
- Kotlin / Jetpack Compose toolchain

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/abdurraheem63/WaveCourt.git
    ```

2. Open the project in **Android Studio**.
3. Sync Gradle and run on a physical device or emulator running Android 13 (API 33) or higher.

---

## License

Distributed under the MIT License. See `LICENSE` for more information.
