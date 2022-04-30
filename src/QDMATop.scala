package qdma

import chisel3._
import chisel3.util._
import common._
import common.storage._
import common.axi._
import common.ToZero

class QDMATop extends RawModule{
	val qdma_pin		= IO(new QDMAPin())
	val led 			= IO(Output(UInt(1.W)))
	val sys_100M_0_p	= IO(Input(Clock()))
  	val sys_100M_0_n	= IO(Input(Clock()))

	led := 0.U

	val mmcm = Module(new MMCME4_ADV_Wrapper(
		CLKFBOUT_MULT_F 		= 20,
		MMCM_DIVCLK_DIVIDE		= 2,
		MMCM_CLKOUT0_DIVIDE_F	= 4,
		MMCM_CLKOUT1_DIVIDE_F	= 10,
		
		MMCM_CLKIN1_PERIOD 		= 10
	))


	mmcm.io.CLKIN1	:= IBUFDS(sys_100M_0_p, sys_100M_0_n)
	mmcm.io.RST		:= 0.U

	val dbg_clk 	= BUFG(mmcm.io.CLKOUT1)
	dontTouch(dbg_clk)

	val user_clk = BUFG(mmcm.io.CLKOUT0)
	val user_rstn = mmcm.io.LOCKED

	val qdma = Module(new QDMA)
	// qdma.getTCL("/home/amax/cj/brand_new_qdma/brand_new_qdma.srcs/sources_1/ip")

	ToZero(qdma.io.reg_status)
	qdma.io.pin <> qdma_pin

	qdma.io.user_clk	:= user_clk
	qdma.io.user_arstn	:= user_rstn
	qdma.io.soft_rstn	:= 1.U

	qdma.io.h2c_data.ready	:= 0.U
	qdma.io.c2h_data.valid	:= 0.U
	qdma.io.c2h_data.bits	:= 0.U.asTypeOf(new C2H_DATA)

	qdma.io.h2c_cmd.valid	:= 0.U
	qdma.io.h2c_cmd.bits	:= 0.U.asTypeOf(new H2C_CMD)
	qdma.io.c2h_cmd.valid	:= 0.U
	qdma.io.c2h_cmd.bits	:= 0.U.asTypeOf(new C2H_CMD)

	// qdma.io.axib.ar.ready	:= 0.U
	// qdma.io.axib.aw.ready	:= 0.U
	// qdma.io.axib.w.ready	:= 0.U

	// qdma.io.axib.r.valid	:= 0.U
	// qdma.io.axib.r.bits		:= 0.U.asTypeOf(qdma.io.axib.r.bits)
	// qdma.io.axib.b.valid	:= 0.U
	// qdma.io.axib.b.bits		:= 0.U.asTypeOf(qdma.io.axib.b.bits)
	val axi_slave = withClockAndReset(qdma.io.pcie_clk,!qdma.io.pcie_arstn){Module(new SimpleAXISlave(new AXIB))}//withClockAndReset(qdma.io.pcie_clk,!qdma.io.pcie_arstn)
	axi_slave.io.axi	<> qdma.io.axib

	val r_data = axi_slave.io.axi.r.bits.data(31,0)

	//count
	withClockAndReset(qdma.io.pcie_clk,!qdma.io.pcie_arstn){
		val count_w_fire = RegInit(0.U(32.W))
		when(qdma.io.axib.w.fire()){
			count_w_fire	:= count_w_fire+1.U
		}
		qdma.io.reg_status(0)	:= count_w_fire
	}
	
	//h2c
	val control_reg = qdma.io.reg_control
	val status_reg = qdma.io.reg_status
	val h2c =  withClockAndReset(qdma.io.user_clk,!qdma.io.user_arstn){Module(new H2C())}

	h2c.io.start_addr	:= Cat(control_reg(100), control_reg(101))
	h2c.io.length		:= control_reg(102)
	h2c.io.offset		:= control_reg(103)
	h2c.io.sop			:= control_reg(104)
	h2c.io.eop			:= control_reg(105)
	h2c.io.start		:= control_reg(106)
	h2c.io.total_words	:= control_reg(107)
	h2c.io.total_qs		:= control_reg(108)
	h2c.io.total_cmds	:= control_reg(109)
	h2c.io.range		:= control_reg(110)
	h2c.io.range_words	:= control_reg(111)
	h2c.io.is_seq		:= control_reg(112)

	for(i <- 0 until 16){
		h2c.io.count_word(i*32+31,i*32)	<> status_reg(102+i)
	}
	h2c.io.count_err	<> status_reg(100)
	h2c.io.count_time	<> status_reg(101)
	h2c.io.h2c_cmd		<> qdma.io.h2c_cmd
	h2c.io.h2c_data		<> qdma.io.h2c_data

	//c2h
	val c2h = withClockAndReset(qdma.io.user_clk,!qdma.io.user_arstn){Module(new C2H())}

	
	c2h.io.start_addr		:= Cat(control_reg(200), control_reg(201))
	c2h.io.length			:= control_reg(202)
	c2h.io.offset			:= control_reg(203)
	c2h.io.start			:= control_reg(204)
	c2h.io.total_words		:= control_reg(205)
	c2h.io.total_qs			:= control_reg(206)
	c2h.io.total_cmds		:= control_reg(207)
	c2h.io.pfch_tag			:= control_reg(209)
	c2h.io.tag_index		:= control_reg(210)
	
	c2h.io.count_cmd		<> status_reg(200)
	c2h.io.count_word		<> status_reg(201)
	c2h.io.count_time		<> status_reg(202)
	c2h.io.c2h_cmd			<> qdma.io.c2h_cmd
	c2h.io.c2h_data			<> qdma.io.c2h_data

}