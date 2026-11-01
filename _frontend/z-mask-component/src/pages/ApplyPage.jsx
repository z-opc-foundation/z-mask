import { useState } from 'react'
import { Alert, Button, Card, Input, Space, Statistic, Table, Tag, Typography, message } from 'antd'
import { EyeInvisibleOutlined } from '@ant-design/icons'
import { maskApi } from '../services/api'

const { TextArea } = Input
const { Paragraph } = Typography

/** 脱敏执行试跑：POST /z-team/api/mask/apply。 */
export default function ApplyPage() {
    const [text, setText] = useState('')
    const [loading, setLoading] = useState(false)
    const [result, setResult] = useState(null)

    const runApply = async () => {
        if (!text.trim()) { message.warning('请输入原文'); return }
        setLoading(true)
        try {
            const { data } = await maskApi.apply(text)
            if (!data.ok) throw new Error(data.message || '脱敏失败')
            setResult(data)
        } catch (e) {
            message.error(`脱敏失败：${e.response?.status || e.message}`)
        } finally {
            setLoading(false)
        }
    }

    const columns = [
        { title: '位置', key: 'pos', width: 110, render: (_, r) => `[${r.start}, ${r.end})` },
        { title: '原文', dataIndex: 'original', width: 160, ellipsis: true },
        { title: '脱敏后', dataIndex: 'masked', width: 160, render: (v) => <Tag color="purple">{v}</Tag> },
        { title: '策略', dataIndex: 'policyId', width: 140 },
        { title: '脱敏类型', dataIndex: 'maskType', width: 120 },
    ]

    return (
        <div>
            <Card title="文本脱敏" style={{ marginBottom: 16 }}>
                <Space direction="vertical" style={{ width: '100%' }} size={12}>
                    <TextArea
                        rows={6}
                        value={text}
                        onChange={(e) => setText(e.target.value)}
                        placeholder="粘贴原文，例如含姓名 / 手机号 / 证件号的段落"
                    />
                    <Button type="primary" icon={<EyeInvisibleOutlined />} loading={loading} onClick={runApply}>
                        执行脱敏
                    </Button>
                </Space>
            </Card>

            {result && (
                <>
                    <Card title="脱敏结果" style={{ marginBottom: 16 }} extra={<Statistic title="命中数" value={result.count} />}>
                        {result.maskedText ? (
                            <Paragraph copyable style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{result.maskedText}</Paragraph>
                        ) : (
                            <Alert type="success" showIcon message="未命中任何脱敏策略，原文可直接使用。" />
                        )}
                    </Card>
                    {result.items?.length > 0 && (
                        <Card title="命中明细">
                            <Table
                                rowKey={(_, i) => i}
                                size="small"
                                columns={columns}
                                dataSource={result.items}
                                pagination={{ pageSize: 10, showSizeChanger: false }}
                            />
                        </Card>
                    )}
                </>
            )}
            {!result && (
                <Paragraph type="secondary" style={{ textAlign: 'center' }}>
                    输入原文后点「执行脱敏」，结果在此展示。
                </Paragraph>
            )}
        </div>
    )
}
