package qdma

import chisel3._
import chisel3.util._
import common._
import common.storage._
import common.axi._
import common.ToZero

object ReporterQDMA extends Reporter{
	override def MAX_NUM = 64
}

class AXIL extends AXI(
	ADDR_WIDTH=32,
	DATA_WIDTH=32,
	ID_WIDTH=0,
	USER_WIDTH=0,
	LEN_WIDTH=0,
){
}

class AXIB extends AXI(
	ADDR_WIDTH=64,
	DATA_WIDTH=512,
	ID_WIDTH=4,
	USER_WIDTH=0,
	LEN_WIDTH=8,
){
}

class TestAXI2Reg extends Module{
	val io = IO(new Bundle{
		val axi = Flipped(new AXIL)
		val reg_control = Output(Vec(16,UInt(32.W))) 
		val reg_status = Input(Vec(16,UInt(32.W)))
	})

	val axi2reg = Module(new PoorAXIL2Reg(new AXIL, 16, 32))

	axi2reg.io <> io
}

class t extends Bundle{
	val data = UInt(32.W)
}
class TestXRam extends Module{
	val io = IO(new Bundle{
		val ramio = new XRamIO(new t, 1024)
	})
	val ram = XRam(new t, 1024)
	ram.io <> io.ramio
}






class QDMA(VIVADO_VERSION:String="2020") extends RawModule{
	
	def getTCL(path:String = "Example: /home/foo/bar.srcs/sources_1/ip") = {
		val s1 = "create_ip -name qdma -vendor xilinx.com -library ip -version 4.0 -module_name QDMABlackBox\n"
		val s2 = "set_property -dict [list CONFIG.Component_Name {QDMABlackBox} CONFIG.axist_bypass_en {true} CONFIG.dsc_byp_mode {Descriptor_bypass_and_internal} CONFIG.testname {st} CONFIG.pf0_bar4_enabled_qdma {true} CONFIG.pf0_bar4_64bit_qdma {true} CONFIG.pf1_bar4_enabled_qdma {true} CONFIG.pf1_bar4_64bit_qdma {true} CONFIG.pf2_bar4_enabled_qdma {true} CONFIG.pf2_bar4_64bit_qdma {true} CONFIG.pf3_bar4_enabled_qdma {true} CONFIG.pf3_bar4_64bit_qdma {true} CONFIG.dma_intf_sel_qdma {AXI_Stream_with_Completion} CONFIG.en_axi_mm_qdma {false}] [get_ips QDMABlackBox]\n"
		val s3 = "generate_target {instantiation_template} [get_files %s/QDMABlackBox/QDMABlackBox.xci]\n"
		val s4 = "update_compile_order -fileset sources_1\n"
		println(s1 + s2 + s3.format(path) + s4)
	}
	val io = IO(new Bundle{
		val pin		= new QDMAPin

		val pcie_clk 	= Output(Clock())
		val pcie_arstn	= Output(Bool())

		val user_clk	= Input(Clock())
		val user_arstn	= Input(Bool())
		val soft_rstn	= Input(Bool())

		val h2c_cmd		= Flipped(Decoupled(new H2C_CMD))
		val h2c_data	= Decoupled(new H2C_DATA)
		val c2h_cmd		= Flipped(Decoupled(new C2H_CMD))
		val c2h_data	= Flipped(Decoupled(new C2H_DATA))

		val reg_control = Output(Vec(512,UInt(32.W)))
		val reg_status	= Input(Vec(512,UInt(32.W)))

		val axib 		= new AXIB
	})

	val perst_n = IBUF(io.pin.sys_rst_n)

	val ibufds_gte4_inst = Module(new IBUFDS_GTE4(REFCLK_HROW_CK_SEL=0))
	ibufds_gte4_inst.io.IB		:= io.pin.sys_clk_n
	ibufds_gte4_inst.io.I		:= io.pin.sys_clk_p
	ibufds_gte4_inst.io.CEB		:= 0.U
	val pcie_ref_clk_gt			= ibufds_gte4_inst.io.O
	val pcie_ref_clk			= ibufds_gte4_inst.io.ODIV2

	val fifo_h2c_data		= XConverter(new H2C_DATA, io.pcie_clk, io.pcie_arstn, io.user_clk)
	fifo_h2c_data.io.out	<> io.h2c_data

	val fifo_c2h_data		= XConverter(new C2H_DATA, io.user_clk, io.user_arstn, io.pcie_clk)
	val fifo_h2c_cmd		= XConverter(new H2C_CMD, io.user_clk, io.user_arstn, io.pcie_clk)
	val fifo_c2h_cmd		= XConverter(new C2H_CMD, io.user_clk, io.user_arstn, io.pcie_clk)

	val check_c2h			= withClockAndReset(io.user_clk,!io.user_arstn){Module(new CMDBoundaryCheck(new C2H_CMD, 0x200000, 0x1000))}//(31*128 Byte)
	check_c2h.io.in			<> io.c2h_cmd
	val check_h2c			= withClockAndReset(io.user_clk,!io.user_arstn){Module(new CMDBoundaryCheck(new H2C_CMD, 0x200000, 0x8000))}
	check_h2c.io.in			<> io.h2c_cmd

	val tlb			= withClockAndReset(io.user_clk,!io.user_arstn){Module(new TLB)}
	// val tlb			= {Module(new TLB)}
	tlb.io.h2c_in	<> check_h2c.io.out
	tlb.io.c2h_in	<> check_c2h.io.out
	tlb.io.h2c_out	<> fifo_h2c_cmd.io.in


	val fifo_wr_tlb		= XConverter(new WR_TLB, io.pcie_clk, io.pcie_arstn, io.user_clk)
	fifo_wr_tlb.io.in.bits		:= Cat(io.reg_control(12), io.reg_control(11), io.reg_control(10), io.reg_control(9), io.reg_control(8)).asTypeOf(fifo_wr_tlb.io.in.bits)
	fifo_wr_tlb.io.in.bits.is_base		:= io.reg_control(12)(0)
	fifo_wr_tlb.io.in.bits.paddr_high	:= io.reg_control(11)
	fifo_wr_tlb.io.in.bits.paddr_low	:= io.reg_control(10)
	fifo_wr_tlb.io.in.bits.vaddr_high	:= io.reg_control(9)
	fifo_wr_tlb.io.in.bits.vaddr_low	:= io.reg_control(8)

	fifo_wr_tlb.io.in.valid		:=  withClockAndReset(io.pcie_clk,!io.pcie_arstn)(RegNext(!RegNext(io.reg_control(13)(0)) & io.reg_control(13)(0)))

	tlb.io.wr_tlb.bits 			:= fifo_wr_tlb.io.out.bits
	tlb.io.wr_tlb.valid 		:= fifo_wr_tlb.io.out.valid
	fifo_wr_tlb.io.out.ready	:= 1.U

	val axil2reg = withClockAndReset(io.pcie_clk,!io.pcie_arstn){Module(new PoorAXIL2Reg(new AXIL, 512, 32))}

	axil2reg.io.reg_status	<> io.reg_status
	axil2reg.io.reg_status(1)	:= tlb.io.tlb_miss_count
	axil2reg.io.reg_control <> io.reg_control

	def record_signals(is_high:Bool,is_reset:Bool,cur_clock:Clock=io.user_clk)={
		withClockAndReset(cur_clock,is_reset){
			val count = RegInit(UInt(32.W),0.U)
			when(is_high){
				count	:= count+1.U
			}
			count
		}
	}
	
	val axib = io.axib
	ToZero(axib.ar.bits)
	ToZero(axib.aw.bits)
	ToZero(axib.w.bits)


	val axil = axil2reg.io.axi
	ToZero(axil.ar.bits)
	ToZero(axil.aw.bits)
	ToZero(axil.w.bits)
	axil.w.bits.last	:= 1.U


	//all refer to c2h
	val boundary_split			= withClockAndReset(io.user_clk,!io.user_arstn){Module(new DataBoundarySplit)}
	boundary_split.io.cmd_in	<> tlb.io.c2h_out
	boundary_split.io.data_in	<> io.c2h_data
	boundary_split.io.cmd_out	<> fifo_c2h_cmd.io.in
	boundary_split.io.data_out	<> fifo_c2h_data.io.in


	val is_reset				= axil2reg.io.reg_control(14) === 1.U
	//c2h cmd counter
	axil2reg.io.reg_status(2)	:= record_signals(io.c2h_cmd.fire(), is_reset)
	axil2reg.io.reg_status(3)	:= record_signals(check_c2h.io.out.fire(), is_reset)
	axil2reg.io.reg_status(4)	:= record_signals(tlb.io.c2h_out.fire(), is_reset)
	axil2reg.io.reg_status(5)	:= record_signals(boundary_split.io.cmd_out.fire(), is_reset)
	axil2reg.io.reg_status(6)	:= record_signals(fifo_c2h_cmd.io.out.fire(), is_reset, io.pcie_clk)

	//h2c cmd counter
	axil2reg.io.reg_status(7)	:= record_signals(io.h2c_cmd.fire(), is_reset)
	axil2reg.io.reg_status(8)	:= record_signals(check_h2c.io.out.fire(), is_reset)
	axil2reg.io.reg_status(9)	:= record_signals(tlb.io.h2c_out.fire(), is_reset)
	axil2reg.io.reg_status(10)	:= record_signals(fifo_h2c_cmd.io.out.fire(), is_reset, io.pcie_clk)

	//c2h data counter
	axil2reg.io.reg_status(11)	:= record_signals(io.c2h_data.fire(), is_reset)
	axil2reg.io.reg_status(12)	:= record_signals(boundary_split.io.data_out.fire(), is_reset)
	axil2reg.io.reg_status(13)	:= record_signals(fifo_c2h_data.io.out.fire(), is_reset, io.pcie_clk)

	//h2c data counter
	axil2reg.io.reg_status(14)	:= record_signals(io.h2c_data.fire(), is_reset)
	axil2reg.io.reg_status(15)	:= record_signals(fifo_h2c_data.io.in.fire(), is_reset, io.pcie_clk)

	//valids and readys
	ReporterQDMA.report(io.c2h_cmd.valid, "io.c2h_cmd.valid")
	ReporterQDMA.report(io.c2h_cmd.ready, "io.c2h_cmd.ready")
	ReporterQDMA.report(check_c2h.io.out.valid, "check_c2h.io.out.valid")
	ReporterQDMA.report(check_c2h.io.out.ready, "check_c2h.io.out.ready")
	ReporterQDMA.report(tlb.io.c2h_out.valid, "tlb.io.c2h_out.valid")
	ReporterQDMA.report(tlb.io.c2h_out.ready, "tlb.io.c2h_out.ready")
	ReporterQDMA.report(boundary_split.io.cmd_out.valid, "boundary_split.io.cmd_out.valid")
	ReporterQDMA.report(boundary_split.io.cmd_out.ready, "boundary_split.io.cmd_out.ready")
	ReporterQDMA.report(fifo_c2h_cmd.io.out.valid, "fifo_c2h_cmd.io.out.valid")
	ReporterQDMA.report(fifo_c2h_cmd.io.out.ready, "fifo_c2h_cmd.io.out.ready")

	ReporterQDMA.report(io.h2c_cmd.valid, "io.h2c_cmd.valid")
	ReporterQDMA.report(io.h2c_cmd.ready, "io.h2c_cmd.ready")
	ReporterQDMA.report(check_h2c.io.out.valid, "check_h2c.io.out.valid")
	ReporterQDMA.report(check_h2c.io.out.ready, "check_h2c.io.out.ready")
	ReporterQDMA.report(tlb.io.h2c_out.valid, "tlb.io.h2c_out.valid")
	ReporterQDMA.report(tlb.io.h2c_out.ready, "tlb.io.h2c_out.ready")
	ReporterQDMA.report(fifo_h2c_cmd.io.out.valid, "fifo_h2c_cmd.io.out.valid")
	ReporterQDMA.report(fifo_h2c_cmd.io.out.ready, "fifo_h2c_cmd.io.out.ready")

	ReporterQDMA.report(io.c2h_data.valid, "io.c2h_cmd.valid")
	ReporterQDMA.report(io.c2h_data.ready, "io.c2h_cmd.ready")
	ReporterQDMA.report(boundary_split.io.data_out.valid, "boundary_split.io.data_out.valid")
	ReporterQDMA.report(boundary_split.io.data_out.ready, "boundary_split.io.data_out.ready")
	ReporterQDMA.report(fifo_c2h_data.io.out.valid, "fifo_c2h_data.io.out.valid")
	ReporterQDMA.report(fifo_c2h_data.io.out.ready, "fifo_c2h_data.io.out.ready")

	ReporterQDMA.report(io.h2c_data.valid, "io.h2c_data.valid")
	ReporterQDMA.report(io.h2c_data.ready, "io.h2c_data.ready")
	ReporterQDMA.report(fifo_h2c_data.io.in.valid, "fifo_h2c_data.io.in.valid")
	ReporterQDMA.report(fifo_h2c_data.io.in.ready, "fifo_h2c_data.io.in.ready")
	
	val reports = withClockAndReset(io.user_clk,!io.user_arstn)(Reg(Vec(ReporterQDMA.MAX_NUM,Bool())))
	ReporterQDMA.get_reports(reports)
	ReporterQDMA.print_msgs()

	axil2reg.io.reg_status(16)	:= reports.asUInt()(31,0)
	axil2reg.io.reg_status(17)	:= reports.asUInt()(63,32)
	
	val qdma_inst = Module(new QDMABlackBox(VIVADO_VERSION))
	qdma_inst.io.sys_rst_n				:= perst_n
	qdma_inst.io.sys_clk				:= pcie_ref_clk
	qdma_inst.io.sys_clk_gt				:= pcie_ref_clk_gt

	qdma_inst.io.pci_exp_txn			<> io.pin.tx_n
	qdma_inst.io.pci_exp_txp			<> io.pin.tx_p
	qdma_inst.io.pci_exp_rxn			:= io.pin.rx_n
	qdma_inst.io.pci_exp_rxp			:= io.pin.rx_p

	qdma_inst.io.axi_aclk				<> io.pcie_clk
	qdma_inst.io.axi_aresetn			<> io.pcie_arstn
	qdma_inst.io.soft_reset_n			:= io.soft_rstn

	//h2c cmd
	qdma_inst.io.h2c_byp_in_st_addr		:= fifo_h2c_cmd.io.out.bits.addr
	qdma_inst.io.h2c_byp_in_st_len		:= fifo_h2c_cmd.io.out.bits.len
	qdma_inst.io.h2c_byp_in_st_eop		:= fifo_h2c_cmd.io.out.bits.eop
	qdma_inst.io.h2c_byp_in_st_sop		:= fifo_h2c_cmd.io.out.bits.sop
	qdma_inst.io.h2c_byp_in_st_mrkr_req	:= fifo_h2c_cmd.io.out.bits.mrkr_req
	qdma_inst.io.h2c_byp_in_st_sdi		:= fifo_h2c_cmd.io.out.bits.sdi
	qdma_inst.io.h2c_byp_in_st_qid		:= fifo_h2c_cmd.io.out.bits.qid
	qdma_inst.io.h2c_byp_in_st_error	:= fifo_h2c_cmd.io.out.bits.error
	qdma_inst.io.h2c_byp_in_st_func		:= fifo_h2c_cmd.io.out.bits.func
	qdma_inst.io.h2c_byp_in_st_cidx		:= fifo_h2c_cmd.io.out.bits.cidx
	qdma_inst.io.h2c_byp_in_st_port_id	:= fifo_h2c_cmd.io.out.bits.port_id
	qdma_inst.io.h2c_byp_in_st_no_dma	:= fifo_h2c_cmd.io.out.bits.no_dma
	qdma_inst.io.h2c_byp_in_st_vld		:= fifo_h2c_cmd.io.out.valid
	qdma_inst.io.h2c_byp_in_st_rdy		<> fifo_h2c_cmd.io.out.ready

	//c2h cmd
	qdma_inst.io.c2h_byp_in_st_csh_addr		:= fifo_c2h_cmd.io.out.bits.addr
	qdma_inst.io.c2h_byp_in_st_csh_qid		:= fifo_c2h_cmd.io.out.bits.qid
	qdma_inst.io.c2h_byp_in_st_csh_error	:= fifo_c2h_cmd.io.out.bits.error
	qdma_inst.io.c2h_byp_in_st_csh_func		:= fifo_c2h_cmd.io.out.bits.func
	qdma_inst.io.c2h_byp_in_st_csh_port_id	:= fifo_c2h_cmd.io.out.bits.port_id
	qdma_inst.io.c2h_byp_in_st_csh_pfch_tag	:= fifo_c2h_cmd.io.out.bits.pfch_tag
	qdma_inst.io.c2h_byp_in_st_csh_vld		:= fifo_c2h_cmd.io.out.valid
	qdma_inst.io.c2h_byp_in_st_csh_rdy		<> fifo_c2h_cmd.io.out.ready

	//c2h data
	qdma_inst.io.s_axis_c2h_tdata			:= fifo_c2h_data.io.out.bits.data
	qdma_inst.io.s_axis_c2h_tcrc			:= fifo_c2h_data.io.out.bits.tcrc
	qdma_inst.io.s_axis_c2h_ctrl_marker		:= fifo_c2h_data.io.out.bits.ctrl_marker
	qdma_inst.io.s_axis_c2h_ctrl_ecc		:= fifo_c2h_data.io.out.bits.ctrl_ecc
	qdma_inst.io.s_axis_c2h_ctrl_len		:= fifo_c2h_data.io.out.bits.ctrl_len
	qdma_inst.io.s_axis_c2h_ctrl_port_id	:= fifo_c2h_data.io.out.bits.ctrl_port_id
	qdma_inst.io.s_axis_c2h_ctrl_qid		:= fifo_c2h_data.io.out.bits.ctrl_qid
	qdma_inst.io.s_axis_c2h_ctrl_has_cmpt	:= fifo_c2h_data.io.out.bits.ctrl_has_cmpt
	qdma_inst.io.s_axis_c2h_mty				:= fifo_c2h_data.io.out.bits.mty
	qdma_inst.io.s_axis_c2h_tlast			:= fifo_c2h_data.io.out.bits.last
	qdma_inst.io.s_axis_c2h_tvalid			:= fifo_c2h_data.io.out.valid
	qdma_inst.io.s_axis_c2h_tready			<> fifo_c2h_data.io.out.ready

	//h2c data
	qdma_inst.io.m_axis_h2c_tdata			<> fifo_h2c_data.io.in.bits.data
	qdma_inst.io.m_axis_h2c_tcrc			<> fifo_h2c_data.io.in.bits.tcrc
	qdma_inst.io.m_axis_h2c_tuser_qid		<> fifo_h2c_data.io.in.bits.tuser_qid
	qdma_inst.io.m_axis_h2c_tuser_port_id	<> fifo_h2c_data.io.in.bits.tuser_port_id
	qdma_inst.io.m_axis_h2c_tuser_err		<> fifo_h2c_data.io.in.bits.tuser_err
	qdma_inst.io.m_axis_h2c_tuser_mdata		<> fifo_h2c_data.io.in.bits.tuser_mdata
	qdma_inst.io.m_axis_h2c_tuser_mty		<> fifo_h2c_data.io.in.bits.tuser_mty
	qdma_inst.io.m_axis_h2c_tuser_zero_byte	<> fifo_h2c_data.io.in.bits.tuser_zero_byte
	qdma_inst.io.m_axis_h2c_tlast			<> fifo_h2c_data.io.in.bits.last
	qdma_inst.io.m_axis_h2c_tvalid			<> fifo_h2c_data.io.in.valid
	qdma_inst.io.m_axis_h2c_tready			:= fifo_h2c_data.io.in.ready

	qdma_inst.io.m_axib_awid				<> axib.aw.bits.id
	qdma_inst.io.m_axib_awaddr				<> axib.aw.bits.addr
	qdma_inst.io.m_axib_awlen				<> axib.aw.bits.len
	qdma_inst.io.m_axib_awsize				<> axib.aw.bits.size
	qdma_inst.io.m_axib_awburst				<> axib.aw.bits.burst
	qdma_inst.io.m_axib_awprot				<> axib.aw.bits.prot
	qdma_inst.io.m_axib_awlock				<> axib.aw.bits.lock
	qdma_inst.io.m_axib_awcache				<> axib.aw.bits.cache
	qdma_inst.io.m_axib_awvalid				<> axib.aw.valid
	qdma_inst.io.m_axib_awready				<> axib.aw.ready

	qdma_inst.io.m_axib_wdata				<> axib.w.bits.data
	qdma_inst.io.m_axib_wstrb				<> axib.w.bits.strb
	qdma_inst.io.m_axib_wlast				<> axib.w.bits.last
	qdma_inst.io.m_axib_wvalid				<> axib.w.valid
	qdma_inst.io.m_axib_wready				<> axib.w.ready

	qdma_inst.io.m_axib_bid					<> axib.b.bits.id
	qdma_inst.io.m_axib_bresp				<> axib.b.bits.resp
	qdma_inst.io.m_axib_bvalid				<> axib.b.valid
	qdma_inst.io.m_axib_bready				<> axib.b.ready

	qdma_inst.io.m_axib_arid				<> axib.ar.bits.id
	qdma_inst.io.m_axib_araddr				<> axib.ar.bits.addr
	qdma_inst.io.m_axib_arlen				<> axib.ar.bits.len
	qdma_inst.io.m_axib_arsize				<> axib.ar.bits.size
	qdma_inst.io.m_axib_arburst				<> axib.ar.bits.burst
	qdma_inst.io.m_axib_arprot				<> axib.ar.bits.prot
	qdma_inst.io.m_axib_arlock				<> axib.ar.bits.lock
	qdma_inst.io.m_axib_arcache				<> axib.ar.bits.cache
	qdma_inst.io.m_axib_arvalid				<> axib.ar.valid
	qdma_inst.io.m_axib_arready				<> axib.ar.ready

	qdma_inst.io.m_axib_rid					<> axib.r.bits.id
	qdma_inst.io.m_axib_rdata				<> axib.r.bits.data
	qdma_inst.io.m_axib_rresp				<> axib.r.bits.resp
	qdma_inst.io.m_axib_rlast				<> axib.r.bits.last
	qdma_inst.io.m_axib_rvalid				<> axib.r.valid
	qdma_inst.io.m_axib_rready				<> axib.r.ready


	qdma_inst.io.m_axil_awaddr				<> axil.aw.bits.addr
	qdma_inst.io.m_axil_awvalid				<> axil.aw.valid
	qdma_inst.io.m_axil_awready				<> axil.aw.ready

	qdma_inst.io.m_axil_wdata				<> axil.w.bits.data
	qdma_inst.io.m_axil_wstrb				<> axil.w.bits.strb
	qdma_inst.io.m_axil_wvalid				<> axil.w.valid
	qdma_inst.io.m_axil_wready				<> axil.w.ready

	qdma_inst.io.m_axil_bresp				<> axil.b.bits.resp
	qdma_inst.io.m_axil_bvalid				<> axil.b.valid
	qdma_inst.io.m_axil_bready				<> axil.b.ready

	qdma_inst.io.m_axil_araddr				<> axil.ar.bits.addr
	qdma_inst.io.m_axil_arvalid				<> axil.ar.valid
	qdma_inst.io.m_axil_arready				<> axil.ar.ready

	qdma_inst.io.m_axil_rdata				<> axil.r.bits.data
	qdma_inst.io.m_axil_rresp				<> axil.r.bits.resp
	qdma_inst.io.m_axil_rvalid				<> axil.r.valid
	qdma_inst.io.m_axil_rready				<> axil.r.ready

	//other

	qdma_inst.io.s_axis_c2h_cmpt_tdata					:= 0.U
	qdma_inst.io.s_axis_c2h_cmpt_size					:= 0.U
	qdma_inst.io.s_axis_c2h_cmpt_dpar					:= 0.U
	qdma_inst.io.s_axis_c2h_cmpt_tvalid					:= 0.U
	qdma_inst.io.s_axis_c2h_cmpt_ctrl_qid				:= 0.U
	qdma_inst.io.s_axis_c2h_cmpt_ctrl_cmpt_type			:= 0.U
	qdma_inst.io.s_axis_c2h_cmpt_ctrl_wait_pld_pkt_id	:= 0.U
	if(qdma_inst.io.s_axis_c2h_cmpt_ctrl_no_wrb_marker != None){
		qdma_inst.io.s_axis_c2h_cmpt_ctrl_no_wrb_marker.get		:= 0.U	
	}
	qdma_inst.io.s_axis_c2h_cmpt_ctrl_port_id			:= 0.U
	qdma_inst.io.s_axis_c2h_cmpt_ctrl_marker			:= 0.U
	qdma_inst.io.s_axis_c2h_cmpt_ctrl_user_trig			:= 0.U
	qdma_inst.io.s_axis_c2h_cmpt_ctrl_col_idx			:= 0.U
	qdma_inst.io.s_axis_c2h_cmpt_ctrl_err_idx			:= 0.U

	qdma_inst.io.h2c_byp_out_rdy			:= 1.U
	qdma_inst.io.c2h_byp_out_rdy			:= 1.U
	qdma_inst.io.tm_dsc_sts_rdy				:= 1.U
	
	qdma_inst.io.dsc_crdt_in_vld				:= 0.U
	qdma_inst.io.dsc_crdt_in_dir				:= 0.U
	qdma_inst.io.dsc_crdt_in_fence				:= 0.U
	qdma_inst.io.dsc_crdt_in_qid				:= 0.U
	qdma_inst.io.dsc_crdt_in_crdt				:= 0.U
	
	qdma_inst.io.qsts_out_rdy					:= 1.U
	
	qdma_inst.io.usr_irq_in_vld					:= 0.U
	qdma_inst.io.usr_irq_in_vec					:= 0.U
	qdma_inst.io.usr_irq_in_fnc					:= 0.U
	//note that above rdy must be 1, otherwise qdma device can not be found in ubuntu, I don't know why
}