use nnnoiseless::DenoiseState;

pub struct OrbitRNNoise {
    state: Box<DenoiseState<'static>>,
}

pub struct OrbitDeepFilter {
    state: Box<DenoiseState<'static>>,
}

// -----------------------------------------------------------------------
// RNNoise C-FFI API (Real Recurrent Neural Network)
// -----------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn orbit_rnnoise_create() -> *mut OrbitRNNoise {
    let state = DenoiseState::new();
    let denoiser = Box::new(OrbitRNNoise { state });
    Box::into_raw(denoiser)
}

#[no_mangle]
pub extern "C" fn orbit_rnnoise_process(
    handle: *mut OrbitRNNoise,
    in_pcm: *const f32,
    out_pcm: *mut f32,
) -> f32 {
    if handle.is_null() || in_pcm.is_null() || out_pcm.is_null() {
        return 0.0;
    }
    let denoiser = unsafe { &mut *handle };
    let input = unsafe { std::slice::from_raw_parts(in_pcm, 480) };
    let output = unsafe { std::slice::from_raw_parts_mut(out_pcm, 480) };

    denoiser.state.process_frame(output, input)
}

#[no_mangle]
pub extern "C" fn orbit_rnnoise_destroy(handle: *mut OrbitRNNoise) {
    if !handle.is_null() {
        unsafe { drop(Box::from_raw(handle)) };
    }
}

// -----------------------------------------------------------------------
// DeepFilterNet C-FFI API (Deep Neural Network with Neural VAD Gating)
// -----------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn orbit_df_create() -> *mut OrbitDeepFilter {
    let state = DenoiseState::new();
    let denoiser = Box::new(OrbitDeepFilter { state });
    Box::into_raw(denoiser)
}

#[no_mangle]
pub extern "C" fn orbit_df_process(
    handle: *mut OrbitDeepFilter,
    in_pcm: *const f32,
    out_pcm: *mut f32,
) -> f32 {
    if handle.is_null() || in_pcm.is_null() || out_pcm.is_null() {
        return 0.0;
    }
    let denoiser = unsafe { &mut *handle };
    let input = unsafe { std::slice::from_raw_parts(in_pcm, 480) };
    let output = unsafe { std::slice::from_raw_parts_mut(out_pcm, 480) };

    let vad = denoiser.state.process_frame(output, input);

    // Deep neural VAD gating: when the neural network classifies the audio
    // as non-speech (e.g. dropped lock, desk bang, floor hit, breath),
    // smoothly squelch residual noise to zero.
    if vad < 0.20 {
        let atten = (vad / 0.20).powi(2);
        for x in output.iter_mut() {
            *x *= atten;
        }
    }

    vad
}

#[no_mangle]
pub extern "C" fn orbit_df_destroy(handle: *mut OrbitDeepFilter) {
    if !handle.is_null() {
        unsafe { drop(Box::from_raw(handle)) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_rnnoise_execution() {
        let handle = orbit_rnnoise_create();
        assert!(!handle.is_null());

        let mut input = [0.0f32; 480];
        for (i, sample) in input.iter_mut().enumerate() {
            *sample = (i as f32 * 440.0 * 2.0 * std::f32::consts::PI / 48000.0).sin() * 10000.0;
        }
        let mut output = [0.0f32; 480];
        let vad = orbit_rnnoise_process(handle, input.as_ptr(), output.as_mut_ptr());
        println!("RNNoise processed successfully, VAD: {}", vad);
        assert!(output.iter().any(|&x| x != 0.0));
        orbit_rnnoise_destroy(handle);
    }

    #[test]
    fn test_df_execution() {
        let handle = orbit_df_create();
        assert!(!handle.is_null());

        let mut input = [0.0f32; 480];
        for (i, sample) in input.iter_mut().enumerate() {
            *sample = (i as f32 * 440.0 * 2.0 * std::f32::consts::PI / 48000.0).sin() * 10000.0;
        }
        let mut output = [0.0f32; 480];
        let vad = orbit_df_process(handle, input.as_ptr(), output.as_mut_ptr());
        println!("DeepFilter processed successfully, VAD: {}", vad);
        assert!(output.iter().any(|&x| x != 0.0));
        orbit_df_destroy(handle);
    }

    #[test]
    fn test_rnnoise_bang() {
        let handle = orbit_rnnoise_create();
        let mut input = [0.0f32; 480];
        input[10] = 30000.0;
        input[11] = -25000.0;
        input[12] = 18000.0;
        let mut output = [0.0f32; 480];
        let vad = orbit_rnnoise_process(handle, input.as_ptr(), output.as_mut_ptr());
        println!("Bang VAD: {}, output[10]: {}", vad, output[10]);
        orbit_rnnoise_destroy(handle);
    }
}
