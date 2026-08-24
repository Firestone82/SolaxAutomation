/* ============================================================================
   Thin fetch wrapper. Every call resolves to the parsed body or rejects with a
   readable message, so callers never have to look at Response objects.
   ========================================================================= */

const Api = (() => {

    async function request(path, options = {}) {
        const response = await fetch(path, {
            headers: {'Accept': 'application/json', ...(options.body ? {'Content-Type': 'application/json'} : {})},
            ...options
        });

        const text = await response.text();
        let body = null;

        if (text) {
            try {
                body = JSON.parse(text);
            } catch (error) {
                body = {message: text};
            }
        }

        if (!response.ok) {
            const message = body?.message || `${response.status} ${response.statusText}`;
            const error = new Error(message);
            error.status = response.status;
            error.body = body;
            throw error;
        }

        return body;
    }

    return {
        config:   () => request('api/config'),
        overview: () => request('api/overview'),
        prices:   () => request('api/prices'),
        weather:  () => request('api/weather'),
        timeline: () => request('api/timeline'),
        modules:  () => request('api/modules'),
        selling:  () => request('api/selling'),

        setModuleEnabled: (id, value) =>
            request(`api/modules/${encodeURIComponent(id)}/enabled?value=${value}`, {method: 'POST'}),

        /** payload: {from, to, watts} for a window, or {startNow, durationMinutes, watts}. */
        armSelling: payload =>
            request('api/selling/arm', {method: 'POST', body: JSON.stringify(payload)}),

        cancelSelling: () => request('api/selling/arm', {method: 'DELETE'}),

        replanSelling: () => request('api/selling/replan', {method: 'POST'}),

        /* -------- direct inverter commands, outside any module -------- */

        setWorkMode: mode =>
            request(`api/inverter/work-mode?mode=${encodeURIComponent(mode)}`, {method: 'POST'}),

        /** payload: {watts, durationMinutes} or {watts, targetSoc} */
        chargeFromGrid: payload =>
            request('api/inverter/charge', {method: 'POST', body: JSON.stringify(payload)}),

        exitRemoteControl: () => request('api/inverter/remote-control/exit', {method: 'POST'})
    };
})();
