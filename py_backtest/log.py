import logging
import colorlog

def setup_logger(level=logging.DEBUG):
    """
    配置并返回一个带有颜色高亮的日志记录器。
    这是一个内部函数，用于创建单例实例。
    """
    # 获取一个特定的logger，而不是root logger，以避免影响第三方库的日志
    log = logging.getLogger("my_app_logger")
    log.setLevel(level)

    # 检查logger是否已经有handlers，防止重复添加
    if log.hasHandlers():
        return log

    # 创建控制台日志处理器
    console_handler = colorlog.StreamHandler()

    # 定义颜色输出格式
    color_formatter = colorlog.ColoredFormatter(
        '%(log_color)s[%(levelname)s] %(message)s',
        log_colors={
            'DEBUG': 'white',
            'INFO': 'green',
            'WARNING': 'yellow',
            'ERROR': 'red',
            'CRITICAL': 'bold_red,bg_white',
        }
    )

    # 将颜色输出格式添加到控制台日志处理器
    console_handler.setFormatter(color_formatter)

    # 将控制台日志处理器添加到logger对象
    log.addHandler(console_handler)

    return log

# --- 单例实例 ---
# 在模块加载时，调用配置函数创建全局唯一的logger实例
logger = setup_logger()