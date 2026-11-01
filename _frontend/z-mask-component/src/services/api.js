/**
 * z-mask API client：走 /z-team/api/mask/** 面（脱敏：apply）。
 */
import {createRequest} from '@yuku123/z-frontend-common'

const request = createRequest({baseURL: '', tokenKey: 'zmask_token'})

export default request

export function configureMask(config) {
    if (config && config.apiBase !== undefined) {
        request.defaults.baseURL = config.apiBase
    }
}

export const maskApi = {
    apply: (text) => request.post('/z-team/api/mask/apply', {text}),
    health: () => request.get('/api/mask/health'),
}
